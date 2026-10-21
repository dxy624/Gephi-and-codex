package org.gephi.plugins.explorer3d;
import org.gephi.project.spi.WorkspaceBytesPersistenceProvider;
import org.gephi.project.api.Workspace;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class Persistence implements WorkspaceBytesPersistenceProvider {
    public String getIdentifier(){return "gephi3d-explorer-v1";}
    public void writeBytes(DataOutputStream out,Workspace w){try{
        final String[] snapshot={null};Runnable capture=()->snapshot[0]=Scene.JSON.toJson(w.getLookup().lookup(Scene.class));
        if(javax.swing.SwingUtilities.isEventDispatchThread())capture.run();else try{javax.swing.SwingUtilities.invokeAndWait(capture);}catch(Exception ex){throw new IOException("Scene snapshot failed",ex);}
        byte[] data=snapshot[0].getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);
    }catch(IOException ex){throw new IllegalStateException("Could not save 3D scene",ex);}}
    public void readBytes(DataInputStream in,Workspace w){try{
        int length=in.readInt();if(length<0||length>100_000_000)throw new IOException("Invalid scene size");byte[] data=new byte[length];in.readFully(data);Scene s=Scene.JSON.fromJson(new String(data,StandardCharsets.UTF_8),Scene.class);if(s!=null){if(s.schema!=1)throw new IOException("Unsupported scene schema");s.workspaceId=w.getId();Scene old=w.getLookup().lookup(Scene.class);if(old!=null)w.remove(old);w.add(s);}
    }catch(IOException|RuntimeException ex){throw new IllegalStateException("Could not restore 3D scene",ex);}}
}
