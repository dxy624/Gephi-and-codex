package org.gephi.plugins.explorer3d;
import org.openide.modules.ModuleInstall;
import javax.swing.SwingUtilities;
import java.util.logging.*;
public final class Installer extends ModuleInstall {
    private transient ApiServer server;
    public void restored(){SwingUtilities.invokeLater(()->{try{Engine.INSTANCE.initialize();server=new ApiServer();server.start();String project=System.getProperty("gephi3d.startProject");if(project!=null)Engine.INSTANCE.worker.submit(()->{Engine.INSTANCE.projects().openProject(new java.io.File(project));SwingUtilities.invokeLater(()->{try{Scene restored=Engine.INSTANCE.scene();java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("netbeans.user"),"restored-scene.json"),Scene.JSON.toJson(restored));}catch(Exception ex){Logger.getLogger(Installer.class.getName()).log(Level.WARNING,"Development restore report failed",ex);}ExplorerPanel.showPanel();});});}catch(Exception ex){Logger.getLogger(Installer.class.getName()).log(Level.SEVERE,"3D Explorer startup failed",ex);}});}
    public void close(){Engine.INSTANCE.cancel.set(true);if(server!=null)server.stop();Engine.INSTANCE.worker.shutdownNow();}
    public void uninstalled(){close();}
}
