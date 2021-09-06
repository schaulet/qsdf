package asdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import lombok.*;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Help;
import picocli.CommandLine.Help.Column;
import picocli.CommandLine.Help.Column.Overflow;
import picocli.CommandLine.Help.TextTable;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.IHelpSectionRenderer;
import picocli.CommandLine.Model.UsageMessageSpec;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class CliCommandHelpRenderer implements IHelpSectionRenderer {
    public static ColorScheme colorScheme;

    public static final int LARGEUR = 20;
    public static final int INDENT = 2;

    static {
        colorScheme = new ColorScheme.Builder()
                .commands(CommandLine.Help.Ansi.Style.bold, CommandLine.Help.Ansi.Style.underline) // combine multiple styles
                .options(CommandLine.Help.Ansi.Style.fg_yellow) // yellow foreground color
                .parameters(CommandLine.Help.Ansi.Style.fg_yellow)
                .optionParams(CommandLine.Help.Ansi.Style.italic)
                .errors(CommandLine.Help.Ansi.Style.fg_red, CommandLine.Help.Ansi.Style.bold)
                .stackTraces(CommandLine.Help.Ansi.Style.italic)
                .build();
    }

    public static ColorScheme getColorScheme(){
        return colorScheme;
    }

    public String render(Help help) {
        CommandSpec spec = help.commandSpec();
        var colorScheme = getColorScheme();
        return render(spec, colorScheme);
    }

    public static  String render(CommandSpec spec, ColorScheme colorScheme) {
        if (spec.subcommands().isEmpty()) { return ""; }

        // prepare layout: two columns
        // the left column overflows, the right column wraps if text is too long
        TextTable textTable = TextTable.forColumns(colorScheme,
                new Column(LARGEUR, INDENT, Overflow.SPAN),
                new Column(spec.usageMessage().width() - LARGEUR, INDENT, Overflow.WRAP));
        textTable.setAdjustLineBreaksForWideCJKCharacters(spec.usageMessage().adjustLineBreaksForWideCJKCharacters());

        for (CommandLine subcommand : spec.subcommands().values()) {
            addHierarchy(subcommand, textTable, "");
        }
        return textTable.toString();
    }

    public static void addHierarchy(CommandLine cmd, TextTable textTable, String ...indent) {
        final String SEP = (indent.length>0)?indent[0]:"";

        // create comma-separated list of command name and aliases
        String names = cmd.getCommandSpec().names().toString();
        names = names.substring(1, names.length() - 1); // remove leading '[' and trailing ']'

        // command description is taken from header or description
        String description = description(cmd.getCommandSpec().usageMessage());
        if (description.contains("<@@@USAGE@@@>")){
            var res = description.split("<@@@USAGE@@@>");
            description = res[0];
            textTable.addRowValues(SEP + names, description);
            textTable.addRowValues("--> Usage:", "");
            Arrays.stream(res).skip(1).forEach(s->{
                var elems = s.split("<@@@DESC@@@>");
                textTable.addRowValues(SEP+ "  " + elems[0], elems[1]);
            });
        } else {
            // add a line for this command to the layout
            textTable.addRowValues(SEP + names, description);
        }


        // add its subcommands (if any)
        for (CommandLine sub : cmd.getSubcommands().values()) {
            addHierarchy(sub, textTable, SEP + "  ");
        }
    }

    public static  String description(UsageMessageSpec usageMessage) {
        if (usageMessage.header().length > 0) {
            return usageMessage.header()[0];
        }
        if (usageMessage.description().length > 0) {
            return usageMessage.description()[0];
        }
        return "";
    }
}

@ApplicationScoped
class CliCommandConfiguration {

    @Produces
    CommandLine produceCommandLine(PicocliCommandLineFactory factory) {
        var cmd = factory.create()
                .setExecutionStrategy(new CommandLine.RunLast());
        setHelperRenderer(cmd, new CliCommandHelpRenderer());
        return cmd;
    }

    void setHelperRenderer(CommandLine cmd, IHelpSectionRenderer renderer){
        cmd.getHelpSectionMap().put(SECTION_KEY_COMMAND_LIST, renderer);
        cmd.getSubcommands().values().forEach(c-> setHelperRenderer(c, renderer));
    }
}

class Log {
    private static boolean[] verbose = {};
    private Log(){};

    public static void setVerbosity(boolean[] verbose) {
        Log.verbose = verbose;
    }

    private static void log(int level, String format, Object ...args){
        if (verbose.length>=level) {
            System.out.printf(format+"\n", args);
        }
    }
    public static void error(String format, Object ...args){
        log(0, "[ERROR]"+format, args);
    }
    public static void info(String format, Object ...args){
        log(0, format, args);
    }
    public static void verbose(String format, Object ...args){
        log(1, "[VERBOSE]"+format, args);
    }
    public static void veryVerbose(String format, Object ...args){
        log(2, "[VERY-VERBOSE]"+format, args);
    }
    public static void debug(String format, Object ...args){
        log(3, "[DEBUG] "+format, args);
    }

    public static SubLog START(){
        return new SubLog();
    }
    static class SubLog{
        private final StringBuilder sb = new StringBuilder();
        public SubLog TAB(int ...occurences){
            var nb = Arrays.stream(occurences).sum();
            sb.append("\t".repeat(nb));
            return this;
        }
        public SubLog MSG(String ...msgs){
            Arrays.stream(msgs).forEach(sb::append);
            return this;
        }

        public TableLog TABLE(){
            return new TableLog(this);
        }

        public EndLog END(){
            return new EndLog(this);
        }
        public SubLog RETURN(){
            sb.append("\n");
            return this;
        }
        public EndLog ENDLINE(){
            RETURN();
            return new EndLog(this);
        }

        static class TableLog {
            private SubLog log;
            private final List<Head> headers = new ArrayList<>();
            private final List<List<String>> rows = new ArrayList<>();
            private int nbCol(){ return headers.size();}
            private int nbRow(){ return rows.size();}

            private TableLog(SubLog log){
                this.log = log;
            }

            public TableLogHeader HEADER(String ...msgs){
                return new TableLogHeader(this).ADD(msgs);
            }

            private SubLog TABLE_END(){
                printLine(log.sb, headers, '|', '-');
                printRow(log.sb, headers, headers.stream().map(h->h.msg).collect(Collectors.toList()), '|');
                printLine(log.sb, headers, '|', '-');
                rows.forEach(r -> {
                    printRow(log.sb, headers, r, '|');
                });
                printLine(log.sb, headers, '|', '-');
                return log;
            }
            private void printLine(StringBuilder sb, List<Head> heads, char SEP, char LINE){
                List<String> lines = new ArrayList<>();
                heads.forEach(h->{
                    StringBuilder sbl = new StringBuilder();
                    for (int j=0;j<h.size;j++){
                        sbl.append(LINE);
                    }
                    lines.add(sbl.toString());
                });
                printRow(sb,heads,lines, SEP);
            }
            private void printRow(StringBuilder sb, List<Head> heads, List<String> row, char CHAR){
                for (int i=0;i<nbCol();i++){
                    sb.append(CHAR);
                    sb.append(heads.get(i).toString(row.get(i)));
                }
                sb.append(CHAR);
                sb.append('\n');
            }

            static class Head{
                private String msg;
                public Head setMsg(String msg){
                    this.msg=msg;
                    return this;
                }
                private int size=15;
                public Head setSize(int size){
                    this.size=size;
                    return this;
                }


                public String toString(String msg){
                    var val = msg.length()-size;
                    if (val > 0){ //Too long
                        return msg.substring(0,size);
                    } else {
                        msg += " ".repeat(Math.abs(val));
                    }
                    return msg;
                }
                public String toString(){
                    return toString(msg);
                }
            }
            static class TableLogHeader{
                private TableLog tableLog;
                private TableLogHeader(TableLog tableLog) {
                    this.tableLog = tableLog;
                }

                public TableLogHeader ADD(String ...headers){
                    return ADD(Arrays.stream(headers).map(s -> new Head().setMsg(s)).collect(Collectors.toList()));
                }
                public TableLogHeader ADD(Head ...headers){
                    return ADD(List.of(headers));
                }
                public TableLogHeader ADD(List<Head> headers){
                    tableLog.headers.addAll(headers);
                    return this;
                }
                public TableLogBody ROW(String ...cells){
                    return new TableLogBody(tableLog).ROW(cells);
                }
            }

            static class TableLogBody{
                private TableLog tableLog;
                private TableLogBody(TableLog tableLog) {
                    this.tableLog = tableLog;
                }

                public TableLogBody ROW(String ...cells){
                    List<String> row = new ArrayList<>();
                    row.addAll(List.of(cells));
                    var val = tableLog.nbCol()-cells.length;
                    if (val > 0){ //not enough cells
                        for (int i=0;i<val;i++){
                            row.add("");
                        }
                    } else if (val < 0){ //too much cells
                        List<String> newRow = new ArrayList<>();
                        for (int j=0;j< tableLog.nbCol();j++){
                            newRow.add(row.get(j));
                        }
                        row = newRow;
                    }
                    tableLog.rows.add(row);
                    return this;
                }

                public SubLog TABLE_END(){
                    return tableLog.TABLE_END();
                }
            }
        }

        static class EndLog{
            private String msg;
            private EndLog(SubLog log){
                msg = log.sb.toString();
            }
            public void info(){
                Log.info(msg);
            }
            public void verbose(){
                Log.verbose(msg);
            }
            public void veryVerbose(){
                Log.veryVerbose(msg);
            }
            public void debug(){
                Log.debug(msg);
            }
        }
    }


}

abstract class Cmd implements Runnable {

    @Option(names = { "-v", "--verbose"}, description = "Verbose mode. Helpful for troubleshooting. Multiple -v options increase the verbosity.")
    private boolean[] verbose= {};

    @Option(names = { "-h", "--help"}, description = "show help.")
    private boolean help;

    @Option(names = { "--proxy-host"}, description = "host proxy to use")
    private String proxyHost;

    @Option(names = { "--proxy-port"}, description = "port proxy to use")
    private int proxyPort;


    @Inject
    Instance<PlugIn> plugIns;

    @Spec
    CommandSpec spec;

    /**
     * @return a Map of registered plugins
     */
    public Map<String, PlugIn> getPlugins(){
        Map<String,PlugIn> mapPlugins = new TreeMap<>();
        plugIns.forEach(pi -> mapPlugins.put(pi.getName(), pi));
        return mapPlugins;
    }

    public void showHelp(){
        var colorScheme = CliCommandHelpRenderer.getColorScheme();
        var subDesc = CliCommandHelpRenderer.render(spec, colorScheme);
        var desc = CliCommandHelpRenderer.description(spec.usageMessage());

        TextTable textTable = TextTable.forColumns(colorScheme,
                new Column(CliCommandHelpRenderer.LARGEUR, CliCommandHelpRenderer.INDENT, Overflow.SPAN),
                new Column(spec.usageMessage().width() - CliCommandHelpRenderer.LARGEUR, CliCommandHelpRenderer.INDENT, Overflow.WRAP));
        textTable.setAdjustLineBreaksForWideCJKCharacters(spec.usageMessage().adjustLineBreaksForWideCJKCharacters());
        CliCommandHelpRenderer.addHierarchy(spec.commandLine(), textTable);
        desc = textTable.toString();
        System.out.printf("%s", desc);
        if (!"".equals(subDesc.trim())) System.out.printf("%s\n", subDesc);
    }


    @Override
    public void run() {
        Log.setVerbosity(verbose);
        if (help){
            showHelp();
            System.exit(0);
        }
        var exitCode = runner();
        System.exit(exitCode);
    }

    abstract int runner();
}



@TopCommand
@Command(name = "qsdf", mixinStandardHelpOptions = true,
        version = "0.1",
        description = "Show all usage help",
        subcommands = {
                CmdPlugin.class,
                CmdInstall.class,
                CmdUninstall.class
        },
        exitCodeListHeading = "Exit Codes:%n",
        exitCodeList = {
                "    0: Successful program execution",
                "    1: Program exit with errors",
                ">1000: Error while installing multiple package. ",
                "       1001-> 1 error, ",
                "       1002-> 2 errors, ",
                "       ..."
        }
)
public class CliCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        var cm = ConfigManager.intance;
        Log.info("Command name from spec: " + spec.name());
        Optional<String> message = ConfigProvider.getConfig().getOptionalValue("greeting.message", String.class);
        if (message.isEmpty()){
            Log.info("Configuration error! value for:");
            Log.info("greeting:");
            Log.info("\tmessage:");
            return;
        }
        Log.info("message=%s\n", message.get());
        Log.info("Working Directory = " + System.getProperty("user.dir"));
        Log.START()
            .TABLE()
            .HEADER("col 1", "col 2")
            .ADD("col3")
            .ROW("1","2","3")
            .ROW("4","5","6","666666")
            .ROW("7")
            .ROW("","8","9")
            .TABLE_END().RETURN()
            .MSG("Tableau 1: coucou").ENDLINE()
            .info();
    }

}



@Command(
        name="plugin",
        description="manage plugins",
        subcommands = {
                CmdPlugin.CmdPluginAdd.class,
                CmdPlugin.CmdPluginList.class,
                CmdPlugin.CmdPluginListAll.class,
                CmdPlugin.CmdPluginRemove.class,
                CmdPlugin.CmdPluginUpdate.class,
                CmdPlugin.CmdPluginUpdateAll.class
        }
)
class CmdPlugin extends Cmd {

    @Override
    public int runner() {
        showHelp();
        return 0;
    }

    @Command(
            name = "add",
            description = "Add a plugin from the plugin repo OR, add a Git repo as a plugin by specifying the name and repo url"
    )
    static class CmdPluginAdd extends Cmd {

        @Parameters(description = "name of the tool")
        private String name = null;

        @Option(names = {"--url"})
        private String url = null;

        @Override
        public int runner() {
            Log.info("configure Plugin for %s", name);
            if (url != null) {
                Log.verbose("url: %s", url);
            }
            //TODO (Plugin) To be implemented
            return 0;
        }
    }


    @Command(
            name = "list",
            description = "List installed plugins. Optionally show git urls and git-ref"
    )
    static class CmdPluginList extends Cmd {

        @Option(names = {"--urls"})
        private String url;

        @Option(names = {"--refs"})
        private String refs;

        @Override
        public int runner() {
            Log.info("Plugin 1");
            Log.info("Plugin 2");
            //TODO (Plugin) To be implemented
            return 0;
        }
    }

    @Command(
            name = "list all",
            description = "List plugins registered on asdf-plugins repository with URLs"
    )
    static class CmdPluginListAll extends Cmd {

        @Override
        public int runner() {
            Log.info("list ALL");
            //TODO (Plugin) To be implemented
            return 0;
        }
    }

    @Command(
            name = "remove",
            description = "Remove plugin and package versions"
    )
    static class CmdPluginRemove extends Cmd {

        @Parameters(description = "name of the tool")
        private String name = null;

        @Override
        public int runner() {
            Log.info("remove Plugin for %s", name);
            //TODO (Plugin) To be implemented
            return 0;
        }
    }

    @Command(
            name = "update",
            description = "Update a plugin to latest commit on default branch or a particular git-ref"
    )
    static class CmdPluginUpdate extends Cmd {

        @Parameters(description = "name of the tool")
        private String name = null;

        @Option(names = {"--url"})
        private String url = null;

        @Override
        public int runner() {
            Log.info("update Plugin for %s", name);
            if (url != null) Log.info("url: %s", url);
            //TODO (Plugin) To be implemented
            return 0;
        }
    }

    @Command(
            name = "update --all",
            description = "Update all plugins to latest commit on default branch"
    )
    static class CmdPluginUpdateAll extends Cmd {

        @Override
        public int runner() {
            Log.info("update all Plugins");
            //TODO (Plugin) To be implemented
            return 0;
        }
    }
}

@Command(
        name="install",
        description="Install packages"+
        "<@@@USAGE@@@>qsdf install<@@@DESC@@@>Install all the package versions listed in the .tool-versions file" +
        "<@@@USAGE@@@>qsdf install <name><@@@DESC@@@>Install one tool at the version specified in the .tool-versions file" +
        "<@@@USAGE@@@>qsdf install <name> <version><@@@DESC@@@>Install a specific version of a package" +
        "<@@@USAGE@@@>qsdf install <name> latest[:<version>]<@@@DESC@@@>Install the latest stable version of a package, or with optional version, install the latest stable version that begins with the given string"
)
class CmdInstall extends Cmd {

    @Parameters(arity="0..1", description= "name of the package")
    private String name=null;

    @Parameters(arity="0..1", description= "version of the package")
    private String version=null;

    @Override
    public int runner() {
        var plugins = getPlugins();
        if (name !=null){
            if (!plugins.containsKey(name)){
                Log.info("No plugin found for %s\n==> Please install corresponding plugin first.", name);
            }
        }
        if (name==null && version==null){
            return installAllFromFile(plugins).getReturnedCode();
        } else if (name !=null && version == null ){
            return installOnePackage(plugins, name).getReturnedCode();
        } else if (name !=null && version.startsWith("latest")){
            return installPackageLatestVersion(plugins, plugins.get(name), name, version).getReturnedCode();
        } else if (name!=null){
            return installTool(plugins, name, version).getReturnedCode();
        } else {
            return 1;
        }
    }

    private Jobs installAllFromFile(Map<String, PlugIn> plugins){
        Log.debug("Install all from File");
        var tools = ConfigManager.intance.getToolsFromFile();
        var jobs = new Jobs();
        jobs.addJob(Action.INSTALL, tools);
        jobs.doJob(plugins, ConfigManager.intance);
        if (jobs.getReturnedCode()==0){
            Log.debug("All install succeeded");
        } else {
            Log.debug("Somme errors append!");
            jobs.stream().filter(j->j.getReturnedCode()!=0)
                    .forEach(j -> {
                        Log.debug(j.addMessage("package=%s", j.getTool().toolName));
                        Log.debug(j.addMessage("version=%s", j.getTool().version));
                        Log.info(j.addMessage("The package %s (version: %s) has not been installed!", j.getTool().toolName, j.getTool().version));
                    });
        }
        return jobs;
    }

    private Job installOnePackage(Map<String, PlugIn> plugIns, String name){
        Log.debug("Install package %s", name);
        var tools = ConfigManager.intance.getToolsFromFile();
        var version=tools.stream().filter(p -> p.toolName.equals(name)).findFirst();
        if (!version.isPresent()){
            Job job = new Job(Action.INSTALL, name, "???");
            Log.info(job.addMessage("You should provide a version in .tool-versions file for the package %s", name));
            job.setReturnedCode(1);
            return job;
        }
        Job job = new Job(Action.INSTALL, version.get());
        job.doJob(plugIns, ConfigManager.intance);
        return job;
    }

    private Job installTool(Map<String, PlugIn> plugIns, String name, String version) {
        Job job = new Job(Action.INSTALL, name, version);
        job.doJob(plugIns, ConfigManager.intance);
        return job;
    }

    private Job installPackageLatestVersion(Map<String, PlugIn> plugIns, PlugIn plugin, String name, String version){
        Log.debug("Install package %s latest version %s", name, version);
        Job job = new Job(Action.INSTALL, name, version);
        var v = plugin.getLastVersionStartingWith(version);
        job.getTool().version=v;
        job.doJob(plugIns, ConfigManager.intance);
        return job;
    }

}


@Command(
        name="uninstall",
        description="uninstall package"+
                "<@@@USAGE@@@>qsdf uninstall<@@@DESC@@@>Install all the package versions listed in the .tool-versions file" +
                "<@@@USAGE@@@>qsdf uninstall <name><@@@DESC@@@>Uninstall a specific version of a tool specified in the .tool-versions file" +
                "<@@@USAGE@@@>qsdf uninstall <name> <version><@@@DESC@@@>Uninstall a specific version of a package"
)
class CmdUninstall extends Cmd {

    @Parameters(arity="0..1", description= "name of the package")
    private String name=null;

    @Parameters(arity="0..1", description= "version of the package")
    private String version=null;

    @Option(names = {"-f","--force"}, description={"force uninstall package"})
    private boolean force=false;

    @Override
    public int runner() {
        var plugins = getPlugins();
        if (name!= null){
            if (!plugins.containsKey(name)){
                Log.info("No plugin for package %s", name);
                return 1;
            }
        }
        if (name==null && version==null){
            if (!force){
                Log.info("use --force if you want to remove all package !");
                return -1;
            } else {
                Log.info("removing all packages!");
                Jobs jobs = new Jobs();
                plugins.keySet().forEach(s->{
                    var tools = ConfigManager.intance.getAllInstalledVersion(s);
                    jobs.addJob(Action.UNINSTALL, tools);
                });
                jobs.doJob(getPlugins(), ConfigManager.intance);
                return jobs.getReturnedCode();
            }
        } else if (name !=null && version == null ){
            Log.info("removing all versions of package %s!", name);
            Jobs jobs = new Jobs();
            var p = plugins.get(name);
            var versions = ConfigManager.intance.getAllInstalledVersion(p.getName());
            jobs.addJob(Action.UNINSTALL, versions);
            jobs.stream().forEach(j -> {
                p.uninstall(j.getTool().version);
            });
            return jobs.getReturnedCode();
        } else {
            var p = plugins.get(name);
            Job job = new Job(Action.UNINSTALL, name, version);
            job.setReturnedCode(p.uninstall(job.getTool().version));
            return job.getReturnedCode();

        }
    }
}


/**
 * This interface configuration force controle of yaml file and mandatory configurations needed
 */
interface Config {

    //    @ConfigMapping(prefix = "greeting")
    interface GreetingConfig {

        //        @WithName("message")
        String message();

    }
}
@Data
@NoArgsConstructor
class DataConfig {
    private Map<String, DataTool> tools = new HashMap<>();

    @Data
    @NoArgsConstructor
    static class DataTool {
        private String name;
        private String global;
        private Set<Version> versions = new LinkedHashSet<>();

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        static class Version {
            private String version;
            private String path;
        }
    }
}
class ConfigManager{

    public static ConfigManager intance = new ConfigManager();
    @Getter
    private final File folder = new File("./config");
    private final File confFile = new File("./config/config.yml");
    @Getter
    @Setter
    private DataConfig conf;

    public ConfigManager(){
        conf = read();
    }

    private DataConfig read () {
        ObjectMapper om = new ObjectMapper(new YAMLFactory());
        try {
            DataConfig conf = om.readValue(confFile, DataConfig.class);
            Log.debug("conf: %s", conf);
            return conf;
        } catch (Exception e ) {
            Log.error(e.getMessage());
            return null;
        }
    }

    /**
     * Sauvegarde la configuration dans un fichier yaml
     */
    public void save(){
        ObjectMapper om = new ObjectMapper(new YAMLFactory());
        try {
            Log.debug("writing conf file");
            var file = new File("./config/config.yml");
            file.getParentFile().mkdirs();
            om.writeValue(file, conf);
        } catch (Exception e){
            Log.error(e.getMessage());
        }
    }

    /**
     * Return a sorted List of all PackageVersion from .tool-versions file
     * @return
     */
    public List<Tool> getToolsFromFile(){
        List<Tool> tools = new ArrayList<>();
        File file = new File(System.getProperty("user.dir"));
        Log.debug("scanning tree");
        while (!isFileContainToolVersions(file) && file==null){
            Log.debug("|--> %s (%s)", file, file.getParentFile());
            file = file.getParentFile();
        }
        if (isFileContainToolVersions(file)){
            File toolVersions = new File(file.toString()+File.separator+".tool-versions");
            try {
                InputStream is = new FileInputStream(toolVersions);
                tools = readFromInputStream(is);
            } catch(Exception e){
                Log.error(e.getMessage());
            }
        }
        Log.debug("tools found in .tool-versions: %s", tools.size());
        return tools;
    }
    private boolean isFileContainToolVersions(File file){
        var res = !Arrays.stream(file.list()).noneMatch(s->".tool-versions".equals(s));
        Log.debug("test: %s", res);
        return res;
    }
    private List<Tool> readFromInputStream(InputStream inputStream) throws IOException {
        List<Tool> tools = new ArrayList<>();
        try (BufferedReader br
                     = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!"".equals(line.trim())){
                    var infos = line.split(" ");
                    var tool = new Tool(infos[0], infos[1]);
                    tools.add(tool);
                }
            }
        }
        return tools;
    }


    /**
     * Return a list of all version installed for the provided package name
     * @param toolName
     * @return
     */
    public List<Tool> getAllInstalledVersion(String toolName){
        var result = conf.getTools().values().stream().map(dt -> dt.getVersions().stream().map(v->new Tool(dt.getName(), v.getVersion())))
                .flatMap(Stream::sorted)
                .sorted()
                .collect(Collectors.toList());
        return result;
    }

    /**
     * This methode check in the yaml file for informations about package and installed versions.
     * If version is found in the yaml file, folder is checked for existenz
     * @param toolName
     * @param version
     * @return
     */
    public boolean isInstalled(String toolName, String version) {
        if (!conf.getTools().containsKey(toolName)){
            return false;
        }
        var tool = conf.getTools().get(toolName);
        if (!tool.getVersions().stream().noneMatch(v -> v.getVersion().equals(version))){
            return true;
        }
        return false;
    }

    /**
     * This methode update the yaml file containing installed resources ref in adding version
     * @param toolName
     * @param version
     * @param path
     */
    public void updateInstalledVersion(String toolName, String version, File path) {
        Log.debug("updating Yaml File for package %s version %s", toolName, version);
        DataConfig.DataTool tool = null;
        if (conf.getTools().containsKey(toolName)){
            Log.debug("The tool %s already exist", toolName);
            tool = conf.getTools().get(toolName);
        } else {
            Log.debug("Add information for tool %s", toolName);
            tool = new DataConfig.DataTool();
            tool.setName(toolName);
            conf.getTools().put(toolName, tool);
        }
        if (tool.getVersions()==null){
            tool.setVersions(new LinkedHashSet<>());
        }
        tool.getVersions().add(new DataConfig.DataTool.Version(version, path.toString()));
        Log.debug("Config: %s", conf);
        ConfigManager.intance.save();
    }

    /**
     * This methode update the yaml file containing installed resources ref in removing version
     * @param toolName
     * @param version
     */
    public void updateUninstalledVersion(String toolName, String version) {
        Log.debug("updating Yaml File removing package %s version %s", toolName, version);
        if (conf.getTools().containsKey(toolName)){
            Log.debug("The tool %s already exist", toolName);
            DataConfig.DataTool tool = conf.getTools().get(toolName);
            var ver = tool.getVersions().stream().filter(v->v.getVersion().equals(version)).findFirst();
            if (ver.isPresent()){
                Log.debug("VVersion found... removing!");
                tool.getVersions().remove(ver);
            }
        } else {
            Log.debug("The tool %s is not configured...", toolName);
        }
        Log.debug("Config: %s", conf);
        ConfigManager.intance.save();
    }

    /**
     * Add a tool with its version to file .tool-versions
     * @param toolName
     * @param version
     */
    public void addToToolVersions(String toolName, String version){
        //TODO create associated command and test !
        File file = new File(System.getProperty("user.dir")+File.separator+".tool-versions");
        List<Tool> tools = new ArrayList<>();
        if (file.exists()){
            try {
                InputStream is = new FileInputStream(file);
                tools.addAll(readFromInputStream(is));
            } catch(Exception e){
                Log.error(e.getMessage());
            }
        }
        var oTool = tools.stream().filter(t->t.toolName.equals(toolName)).findFirst();
        if (oTool.isPresent()){
            oTool.get().version=version;
        } else {
            tools.add(new Tool(toolName, version));
        }
        try {
            FileWriter fw = new FileWriter(file);
            tools.stream().sorted().forEach(t->{
                try {
                    fw.write(t.toolName+" "+t.version+"\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }catch(Exception e){
            e.printStackTrace();
        }
    }
}

/**
 * Iterface for tools
 */
interface PlugIn {
    /**
     * The name of the tool
     * @return
     */
    String getName();

    default String getLastVersionStartingWith(String ...version) {
        var remotes = getAllRemoteVersions();
        if (remotes.size()==0){
            System.out.printf("No version found for package %s\n", getName());
            System.exit(1);
        }
        //On trie
        remotes = remotes.stream().sorted((a,b)->-1*a.compareTo(b)).collect(Collectors.toList());
        if (version.length>0){
            //On supprime ceux qui ne commencent pas par le prefix
            var ver = version[0];
            remotes = remotes.stream().filter(v->v.startsWith(ver)).collect(Collectors.toList());
        }
        var latest = remotes.stream().findFirst().get();
        return latest;
    }

    default File getFolderForVersion(String version){
        File folder = new File(ConfigManager.intance.getFolder().toString()+File.separator+"installation"+File.separator+getName()+File.separator+version);
        return folder;
    }
    default File getFolderShim(){
        File folder = new File(ConfigManager.intance.getFolder().toString()+File.separator+"shims");
        return folder;
    }

    List<String> getAllRemoteVersions();

    boolean isVersionInstallable(String version);

    int install(String version);

    int createShims();

    int deleteShims();

    int uninstall(String version);

    File getPath(String version);

    boolean isInstalled(String version);
}

@AllArgsConstructor
class Tool implements Comparable<Tool>{
    public String toolName;
    public String provider="default";
    public String version;
    public String url="";

    public Tool(String toolName, String version){
            this.toolName = toolName;
            this.version=version;
    }

    @Override
    public int compareTo(Tool o) {
        int res = toolName.compareTo(o.toolName);
        if (res==0){
            res = version.compareTo(o.version);
        }
        if (res==0){
            res = provider.compareTo(o.provider);
        }
        return res;
    }
}

enum Action {
    PLUGIN_ADD, PLUGIN_LIST, PLUGIN_REMOVE, PLUGIN_UPDATE,
    INSTALL, UNINSTALL, CURRENT, WHERE, WHICH,
    LOCAL, SHELL,
    LATEST, LIST,
    HELP,
    EXEC, ENV, INFO, RE_SHIM, SHIM_VERSIONS, UPDATE, UPDATE_HEAD;

    public Job doAction(Map<String,PlugIn> plugIns, ConfigManager config, Job job){
        String toolName=job.getTool().toolName;
        //Check for plugin
        if (!plugIns.containsKey(toolName)){
            Log.info(job.addMessage("No plugin found for %s\n==> Please install corresponding plugin first.", toolName));
            job.setReturnedCode(1);
            return job;
        }
        var plugIn = plugIns.get(toolName);
        switch (this) {
            case INSTALL:
                return actionInstallTool(plugIn, config, job);
            case UNINSTALL:
                return actionUninstallTool(plugIn, config, job);
            case EXEC:
                return actionExecTool(plugIn, config, job);
            default:
                job.addMessage("Action %s is not implemented", this.name());
        }
        return job;
    }

    private Job actionExecTool(PlugIn plugIn, ConfigManager config, Job job) {
        return job;
    }

    /**
     * Ask a plugin to install a specific version of the tool managed by this plugin
     * @param plugIn the plugin needed to do the real job
     * @param config the configManager
     * @param job the job contains informations like Action, tool name, version
     * @return
     */
    private Job actionInstallTool(PlugIn plugIn, ConfigManager config, Job job) {
        String toolName=job.getTool().toolName;
        String version = job.getTool().version;
        Log.info("Installing %s version %s", toolName, version);
        var physicalInst = plugIn.isInstalled(version);
        Log.debug("|--> version physically installed: %s", physicalInst);
        var configDeclaration = config.isInstalled(toolName, version);
        Log.debug("|--> version declared in config: %s", configDeclaration);
        var stop  = physicalInst && configDeclaration;
        if (stop){
            Log.verbose(job.addMessage("The package %s (version: %s) is already installed. If you want to reinstall, please remove package first.", toolName, version));
            job.setReturnedCode(0);
            return job;
        }
        stop = !plugIn.isVersionInstallable(version);
        if (stop){
            Log.info(job.addMessage("The package %s with version %s can not be found by plugin", toolName, version));
            job.setReturnedCode(1);
            return job;
        }
        var exitCode =  plugIn.install(version);
        if (exitCode==0){
            config.updateInstalledVersion(toolName, version, plugIn.getPath(version));
        }
        job.setReturnedCode(exitCode);
        return job;
    }

    /**
     * Ask a plugin to install a specific version of the tool managed by this plugin
     * @param plugIn the needed plugin
     * @param config the configManager
     * @param job the job contains informations like Action, tool name, version
     * @return the job containing result informations
     */
    private Job actionUninstallTool(PlugIn plugIn, ConfigManager config, Job job) {
        String toolName=job.getTool().toolName;
        String version = job.getTool().version;
        Log.info("Uninstalling %s version %s", toolName, version);
        var stop  = !(plugIn.isInstalled(version) && config.isInstalled(toolName, version));
        if (stop){
            Log.verbose(job.addMessage("The package %s (version: %s) is not installed.", toolName, version));
            job.setReturnedCode(0);
            return job;
        }
        var exitCode =  plugIn.uninstall(version);
        if (exitCode==0){
            //We save modification with configManager
            config.updateUninstalledVersion(toolName, version);
        }
        job.setReturnedCode(exitCode);
        return job;
    }
}

@Data
@NoArgsConstructor
class Job{
    private Tool tool;
    private List<String> messages = new ArrayList<>();
    private Action action;
    private int returnedCode=-1;

    public String addMessage(String format, Object ...args){
        var msg = String.format(format, args);
        messages.add(msg);
        return msg;
    }

    public Job(Action action, Tool tool) {
        this.tool = tool;
        this.action = action;
    }
    public Job(Action action, String packageName, String tool) {
        this(action, new Tool(packageName, tool));
    }

    /**
     * Run the job using list of plugins and ConfigManager
     * @param plugIns
     * @param config
     * @return the job containing result informations
     */
    public Job doJob(Map<String,PlugIn> plugIns, ConfigManager config){
        action.doAction(plugIns, config, this);
        return this;
    }

}

class Jobs{
    @Getter
    private List<Job> jobs = new ArrayList<>();

    public Stream<Job> stream(){
        return jobs.stream();
    }

    public Jobs addJob(Job ...jobs){
        addJob(List.of(jobs));
        return this;
    }
    public Jobs addJob(List<Job> jobs){
        this.jobs.addAll(jobs);
        return this;
    }
    public Jobs addJob(Action action, Tool tool){
        addJob(new Job(action, tool));
        return this;
    }
    public Jobs addJob(Action action, Tool...tools){
        addJob(List.of(action), List.of(tools));
        return this;
    }
    public Jobs addJob(Action action, List<Tool> tools) {
        addJob(List.of(action), tools);
        return this;
    }
    public Jobs addJob(List<Action> actions, Tool tool){
        addJob(actions, List.of(tool));
        return this;
    }
    public Jobs addJob(List<Action> actions, List<Tool> tools) {
        tools.forEach(t -> actions.forEach(action ->addJob(action, t)));
        return this;
    }
    public Jobs addJob(List<Action> actions, String toolName, String version){
        addJob(actions, new Tool(toolName, version));
        return this;
    }
    public Jobs addJob(Action action, String toolName, String version){
        return addJob(action, new Tool(toolName, version));
    }

    public Jobs doJob(Map<String,PlugIn> plugIns, ConfigManager config){
        stream().forEach(j -> {
            j.doJob(plugIns, config);
        });
        return this;
    }


    /**
     * the number of errors
     * @return
     */
    public int getReturnedCode(){
        int returnedCode = 1000+jobs.stream()
                .mapToInt(j->j.getReturnedCode())
                .filter(i->i!=0) //filter on errors
                .map(i-> 1).sum(); //count
        if (returnedCode==1000) {
            returnedCode=0;
        }
        return returnedCode;
    }
}