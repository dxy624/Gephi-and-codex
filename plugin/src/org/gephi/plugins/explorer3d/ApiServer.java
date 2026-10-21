package org.gephi.plugins.explorer3d;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.swing.SwingUtilities;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only, bearer-token bridge. No cross-origin browser access. */
public final class ApiServer {
    private HttpServer server;private ExecutorService executor;private String token;
    private final Map<String,String> replies=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String,String> requestBodies=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Object commandLock=new Object();
    public static int port(){return Integer.getInteger("gephi3d.port",8183);}
    public void start() throws IOException {
        Path tokenPath=Path.of(System.getProperty("netbeans.user"),"config","gephi3d-token");Files.createDirectories(tokenPath.getParent());
        if(Files.exists(tokenPath))token=Files.readString(tokenPath).trim();else{token=UUID.randomUUID().toString()+UUID.randomUUID();Files.writeString(tokenPath,token,StandardOpenOption.CREATE_NEW);}
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port()),8);
        executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Gephi-3D-API");t.setDaemon(true);return t;});server.setExecutor(executor);
        server.createContext("/",this::handle);server.start();
    }
    public void stop(){if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    void handle(HttpExchange x) throws IOException {
        try{
            if(x.getRequestHeaders().containsKey("Origin")){send(x,403,"{\"success\":false,\"error\":\"Browser-origin requests are not accepted\"}");return;}
            String authorization=x.getRequestHeaders().getFirst("Authorization");
            if(authorization==null || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))){send(x,401,"{\"success\":false,\"error\":\"Local bridge token required\"}");return;}
            String path=x.getRequestURI().getPath();
            if(x.getRequestMethod().equals("GET")&&(path.equals("/health")||path.equals("/state"))){send(x,200,Scene.JSON.toJson(edt(()->Engine.INSTANCE.state())));return;}
            if(!x.getRequestMethod().equals("POST")||!path.equals("/command")){send(x,404,"{\"success\":false,\"error\":\"Unknown endpoint\"}");return;}
            if(!Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json")){send(x,415,"{\"success\":false,\"error\":\"JSON required\"}");return;}
            byte[] bytes=x.getRequestBody().readNBytes(1_000_001);if(bytes.length>1_000_000){send(x,413,"{\"success\":false,\"error\":\"Request too large\"}");return;}
            JsonObject request=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            String id=Engine.str(request,"request_id","");if(id.isEmpty())throw new IllegalArgumentException("request_id is required");
            String response;
            // Serialize mutations and request deduplication; keep import parsing off the EDT.
            synchronized(commandLock){
            String prior=replies.get(id);
            if(prior!=null){if(!requestBodies.get(id).equals(request.toString()))throw new IllegalArgumentException("request_id was already used for a different command");send(x,200,prior);return;}
            String operation=Engine.str(request,"operation","");
            if(operation.equals("import_graph")){
                org.gephi.project.api.Workspace previous=(org.gephi.project.api.Workspace)edt(()->{if(Engine.INSTANCE.jobState.equals("running"))throw new IllegalArgumentException("Pause layout before importing");return Engine.INSTANCE.workspace();});
                GraphImport imported=GraphImport.read(Engine.str(request,"path",""));
                response=Scene.JSON.toJson(edt(()->imported.commit(previous,Engine.bool(request,"synthetic",false))));
            }else if(operation.equals("save_project")){
                org.gephi.project.api.Workspace w=(org.gephi.project.api.Workspace)edt(()->{Engine e=Engine.INSTANCE;if(e.workspace()==null||e.workspace().getId()!=Engine.integer(request,"workspace_id",-1))throw new IllegalArgumentException("Workspace changed");if(e.jobState.equals("running"))throw new IllegalArgumentException("Pause layout before saving");return e.workspace();});
                Path destination=Path.of(Engine.str(request,"path",""));if(!destination.isAbsolute()||!destination.toString().endsWith(".gephi")||Files.exists(destination))throw new IllegalArgumentException("New absolute .gephi path required");
                Engine.INSTANCE.projects().saveProject(w.getProject(),destination.toFile());
                response=Scene.JSON.toJson(Map.of("success",Files.isRegularFile(destination),"path",destination.toString()));
            }else{
                response=Scene.JSON.toJson(edt(()->Engine.INSTANCE.command(request)));
            }
            replies.put(id,response);requestBodies.put(id,request.toString());if(replies.size()>128){String oldest=replies.keySet().iterator().next();replies.remove(oldest);requestBodies.remove(oldest);}
            }
            send(x,200,response);
        }catch(Exception ex){Throwable cause=ex;while(cause.getCause()!=null)cause=cause.getCause();send(x,400,Scene.JSON.toJson(Map.of("success",false,"error",String.valueOf(cause.getMessage()))));}
        finally{x.close();}
    }
    interface Task {Object run() throws Exception;}
    Object edt(Task task) throws Exception {if(SwingUtilities.isEventDispatchThread())return task.run();FutureTask<Object> future=new FutureTask<>(task::run);SwingUtilities.invokeLater(future);return future.get();}
    void send(HttpExchange x,int status,String text) throws IOException {byte[] data=text.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);}
}
