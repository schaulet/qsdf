package asdf;

import javax.enterprise.context.ApplicationScoped;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class PlugInJava implements PlugIn {
    @Override
    public String getName() {
        return "java";
    }

    @Override
    public List<String> getAllRemoteVersions() {
        //TODO To be implemented
        List<String> versions =  new ArrayList<>();
        versions.addAll(List.of("1","2","3","4"));
        return versions;
    }

    @Override
    public boolean isVersionInstallable(String version) {
        if(getAllRemoteVersions().contains(version)){
            return true;
        }
        return false;
    }

    @Override
    public int install(String version) {
        //TODO To be implemented
        getFolderForVersion(version).mkdirs();
        createShims();
        return 0;
    }

    @Override
    public int createShims() {
        //TODO To be implemented
        getFolderShim().mkdirs();
        String path = getFolderShim().toString();
        File java = new File(path+File.separator+"java.cmd");
        try{java.createNewFile();}catch(Exception e){}
        return 0;
    }

    @Override
    public int deleteShims() {
        //TODO To be implemented
        String path = getFolderShim().toString();
        File java = new File(path+File.separator+"java.cmd");
        try{java.delete();}catch(Exception e){}
        return 0;
    }

    @Override
    public int uninstall(String version) {
        //TODO To be implemented
        getFolderForVersion(version).delete();
        deleteShims();
        return 0;
    }

    @Override
    public File getPath(String version) {
        //TODO To be implemented
        return getFolderForVersion(version);
    }

    @Override
    public boolean isInstalled(String version) {
        //TODO To be implemented (make a beter check)
        return getFolderForVersion(version).exists();
    }
}
