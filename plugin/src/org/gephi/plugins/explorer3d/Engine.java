package org.gephi.plugins.explorer3d;

import com.google.gson.*;
import org.gephi.graph.api.*;
import org.gephi.project.api.*;
import org.openide.util.Lookup;
import javax.swing.*;
import java.awt.Color;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Engine {
    public static final Engine INSTANCE=new Engine();
    private final Map<Integer,Deque<Scene>> undo=new HashMap<>(), redo=new HashMap<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Gephi-3D-layout");t.setDaemon(true);return t;});
    volatile String jobId="",jobState="idle",jobError="";volatile int iteration;
    AtomicBoolean cancel=new AtomicBoolean();
    public Runnable changed=()->{};
    public ProjectController projects(){return Lookup.getDefault().lookup(ProjectController.class);}
    public Workspace workspace(){return projects().getCurrentWorkspace();}
    public Scene scene(){Workspace w=workspace();if(w==null)return null;Scene s=w.getLookup().lookup(Scene.class);if(s==null){s=new Scene();s.workspaceId=w.getId();w.add(s);sync(s,w);}return s;}
    GraphModel model(Workspace w){return Lookup.getDefault().lookup(GraphController.class).getGraphModel(w);}
    public void initialize(){projects().addWorkspaceListener(new WorkspaceListener(){
        public void initialize(Workspace w){} public void select(Workspace w){changed.run();}
        public void unselect(Workspace w){cancel.set(true);} public void close(Workspace w){cancel.set(true);undo.remove(w.getId());redo.remove(w.getId());}
        public void disable(){cancel.set(true);changed.run();}
    });}
    void sync(Scene s,Workspace w){
        Graph graph=model(w).getGraph();Map<String,Scene.Vertex> old=new HashMap<>();for(Scene.Vertex v:s.nodes)old.put(v.id,v);
        List<Scene.Vertex> nodes=new ArrayList<>();List<Scene.Link> edges=new ArrayList<>();Map<Object,Integer> index=new HashMap<>();Random rng=new Random(s.seed);
        graph.readLock();try{
            for(Node n:graph.getNodes()){
                String id=n.getId().toString();Scene.Vertex v=old.get(id);
                if(v==null){v=new Scene.Vertex();v.id=id;v.x=(rng.nextDouble()-.5)*500;v.y=(rng.nextDouble()-.5)*500;v.z=(rng.nextDouble()-.5)*500;Color c=n.getColor();v.r=c.getRed()/255f;v.g=c.getGreen()/255f;v.b=c.getBlue()/255f;v.size=Math.max(4,Math.min(30,n.size()));}
                v.label=n.getLabel()==null||n.getLabel().isEmpty()?id:n.getLabel();v.degree=graph.getDegree(n);v.attributes.clear();
                for(Column c:n.getAttributeColumns())if(!c.isProperty()){Object value=n.getAttribute(c);if(value!=null)v.attributes.put(c.getId(),String.valueOf(value));}
                v.community=v.attributes.getOrDefault("community",v.attributes.getOrDefault("modularity_class",v.community));
                index.put(n.getId(),nodes.size());nodes.add(v);
            }
            for(Edge e:graph.getEdges()){Integer a=index.get(e.getSource().getId()),b=index.get(e.getTarget().getId());if(a==null||b==null)continue;Scene.Link l=new Scene.Link();l.id=e.getId().toString();l.source=a;l.target=b;l.directed=e.isDirected();l.weight=Double.isFinite(e.getWeight())?e.getWeight():1;edges.add(l);}
        }finally{graph.readUnlock();}
        s.nodes=nodes;s.edges=edges;s.selection.retainAll(oldIds(nodes));s.highlight.retainAll(oldIds(nodes));s.pathEdges.clear();
        if(old.isEmpty())s.fit();
    }
    Set<String> oldIds(List<Scene.Vertex> nodes){Set<String> ids=new HashSet<>();for(Scene.Vertex v:nodes)ids.add(v.id);return ids;}
    void remember(Scene s){Deque<Scene> q=undo.computeIfAbsent(s.workspaceId,k->new ArrayDeque<>());q.push(s.copy());while(q.size()>15)q.removeLast();redo.computeIfAbsent(s.workspaceId,k->new ArrayDeque<>()).clear();}
    void replace(Scene before,Scene after){Workspace w=workspace();w.remove(before);after.revision=before.revision+1;w.add(after);changed.run();}
    void history(boolean backwards){cancel.set(true);Scene s=requireScene();Map<Integer,Deque<Scene>> a=backwards?undo:redo,b=backwards?redo:undo;Deque<Scene> q=a.computeIfAbsent(s.workspaceId,k->new ArrayDeque<>());if(q.isEmpty())throw new IllegalArgumentException("No history available");b.computeIfAbsent(s.workspaceId,k->new ArrayDeque<>()).push(s.copy());replace(s,q.pop());}
    Scene requireScene(){Scene s=scene();if(s==null)throw new IllegalArgumentException("No workspace open. Generate an example or open a project first.");return s;}
    public Map<String,Object> state(){Scene s=scene();Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("version","0.1.0-alpha");result.put("has_workspace",s!=null);result.put("scene",s==null?null:s.status());result.put("layout",Map.of("job_id",jobId,"state",jobState,"iteration",iteration,"error",jobError));result.put("renderer",ExplorerPanel.rendererStatus);return result;}
    public Object command(JsonObject j){
        String operation=str(j,"operation","");
        if(Set.of("open","generate","search","node","cancel_layout","save_scene","export_png","benchmark").contains(operation))return commandInternal(j);
        Scene check=requireScene();
        if(integer(j,"workspace_id",-1)!=check.workspaceId)throw new IllegalArgumentException("Workspace changed or missing workspace_id. Read state before modifying this scene.");
        if(j.has("expected_revision")&&(long)num(j,"expected_revision",-1)!=check.revision)throw new IllegalArgumentException("Scene changed. Read current state and retry.");
        if(jobState.equals("running")&&Set.of("layout","refresh","layer","pin").contains(operation))throw new IllegalArgumentException("A layout is running. Cancel it first.");
        Scene before=scene();Scene backup=before==null?null:before.copy();
        Deque<Scene> u=before==null?null:new ArrayDeque<>(undo.getOrDefault(before.workspaceId,new ArrayDeque<>()));
        Deque<Scene> r=before==null?null:new ArrayDeque<>(redo.getOrDefault(before.workspaceId,new ArrayDeque<>()));
        try{return commandInternal(j);}catch(RuntimeException ex){cancel.set(true);if(before!=null&&workspace()!=null&&workspace().getId()==before.workspaceId){Scene current=scene();workspace().remove(current);workspace().add(backup);undo.put(backup.workspaceId,u);redo.put(backup.workspaceId,r);changed.run();}throw ex;}
    }
    private Object commandInternal(JsonObject j){
        String op=str(j,"operation","");
        if(op.equals("open")){ExplorerPanel.showPanel();return state();}
        if(op.equals("generate")){generate(str(j,"template","community"),integer(j,"nodes",60),integer(j,"communities",3),num(j,"average_degree",8),(long)num(j,"seed",42));changed.run();ExplorerPanel.showPanel();return state();}
        Scene s=requireScene();
        if(!j.has("workspace_id") || integer(j,"workspace_id",-1)!=s.workspaceId)throw new IllegalArgumentException("Workspace changed or missing workspace_id. Read state before modifying this scene.");
        if(j.has("expected_revision") && (long)num(j,"expected_revision",-1)!=s.revision)throw new IllegalArgumentException("Scene changed. Read current state and retry.");
        if(op.equals("search")){String query=str(j,"query","").toLowerCase(Locale.ROOT);List<Scene.Vertex> matches=new ArrayList<>();for(Scene.Vertex n:s.nodes)if(n.id.toLowerCase(Locale.ROOT).contains(query)||n.label.toLowerCase(Locale.ROOT).contains(query)){matches.add(n);if(matches.size()>=100)break;}return Map.of("success",true,"matches",matches);}
        if(op.equals("node"))return Map.of("success",true,"node",s.find(str(j,"id","")));
        if(op.equals("cancel_layout")){cancel.set(true);return state();}
        if(op.equals("undo")||op.equals("redo")){history(op.equals("undo"));return state();}
        if(op.equals("export_png")){if(jobState.equals("running"))throw new IllegalArgumentException("Pause the layout before exporting");return ExplorerPanel.exportPng(str(j,"path",""),integer(j,"width",6000),integer(j,"height",4000));}
        if(op.equals("save_scene"))return Map.of("success",true,"scene",s);
        if(op.equals("benchmark"))return ExplorerPanel.benchmark();
        if(jobState.equals("running") && Set.of("layout","refresh","layer","pin").contains(op))throw new IllegalArgumentException("A layout is running. Cancel it first.");
        remember(s);Object detail=null;
        switch(op){
            case "refresh":sync(s,workspace());break;
            case "select":s.selection.clear();for(String id:strings(j,"ids")){s.find(id);s.selection.add(id);}break;
            case "focus":{
                List<String> ids=strings(j,"ids");if(ids.isEmpty())s.fit();else{double x=0,y=0,z=0;for(String id:ids){Scene.Vertex n=s.find(id);x+=n.x;y+=n.y;z+=n.z;}s.targetX=x/ids.size();s.targetY=y/ids.size();s.targetZ=z/ids.size();s.distance=Math.max(70,s.distance*.55);}break;}
            case "camera":s.yaw=num(j,"yaw",s.yaw);s.pitch=Math.max(-1.5,Math.min(1.5,num(j,"pitch",s.pitch)));s.distance=Math.max(20,Math.min(100000,num(j,"distance",s.distance)));s.perspective=bool(j,"perspective",s.perspective);break;
            case "neighbors":s.neighbors(str(j,"id",""),integer(j,"hops",1));s.isolate=bool(j,"isolate",false);break;
            case "path":detail=s.shortestPath(str(j,"source",""),str(j,"target",""),bool(j,"directed",true));s.isolate=bool(j,"isolate",false);break;
            case "highlight_community":s.highlight.clear();s.pathEdges.clear();Set<String> communities=new HashSet<>(strings(j,"communities"));for(Scene.Vertex n:s.nodes)if(communities.contains(n.community))s.highlight.add(n.id);s.isolate=bool(j,"isolate",false);break;
            case "clear_highlight":s.highlight.clear();s.pathEdges.clear();s.isolate=false;break;
            case "filter":s.minDegree=Math.max(0,integer(j,"min_degree",0));s.minWeight=num(j,"min_weight",-Double.MAX_VALUE);s.communityFilter=str(j,"community","");break;
            case "pin":for(String id:strings(j,"ids"))s.find(id).pinned=bool(j,"pinned",true);break;
            case "layer":s.layer(str(j,"attribute","community"),num(j,"spacing",160));break;
            case "unlayer":s.layerAttribute="";break;
            case "style":s.dark=bool(j,"dark",s.dark);s.labels=bool(j,"labels",s.labels);s.nodeScale=Math.max(.2,Math.min(5,num(j,"node_scale",s.nodeScale)));s.edgeWidth=Math.max(.5,Math.min(5,num(j,"edge_width",s.edgeWidth)));break;
            case "bookmark":{String name=str(j,"name","view");if(bool(j,"restore",false)){double[] c=s.bookmarks.get(name);if(c==null)throw new IllegalArgumentException("Unknown bookmark");s.yaw=c[0];s.pitch=c[1];s.distance=c[2];s.targetX=c[3];s.targetY=c[4];s.targetZ=c[5];s.perspective=c[6]>0;}else s.bookmarks.put(name,new double[]{s.yaw,s.pitch,s.distance,s.targetX,s.targetY,s.targetZ,s.perspective?1:0});break;}
            case "layout":startLayout(s,Math.min(1000,Math.max(1,integer(j,"iterations",500))),Math.min(30000,Math.max(100,num(j,"milliseconds",30000))));break;
            default:throw new IllegalArgumentException("Unknown operation: "+op);
        }
        s.revision++;changed.run();Map<String,Object> result=state();if(detail!=null)result.put("result",detail);return result;
    }
    void startLayout(Scene s,int steps,double ms){
        Scene snapshot=s.copy();AtomicBoolean token=new AtomicBoolean();cancel=token;jobId=UUID.randomUUID().toString();jobState="running";jobError="";iteration=0;
        String id=jobId;
        worker.submit(()->{try{
            double[][] result=ForceLayout.run(snapshot,steps,(long)ms,token::get,(i,p)->{if(jobId.equals(id))iteration=i;SwingUtilities.invokeLater(()->{if(!token.get() && scene()==s){apply(s,p);changed.run();}});});
            SwingUtilities.invokeLater(()->{if(!token.get() && scene()==s){apply(s,result);s.revision++;}if(jobId.equals(id))jobState=token.get()?"cancelled":"completed";changed.run();});
        }catch(Exception ex){if(jobId.equals(id)){jobError=ex.toString();jobState="failed";}}});
    }
    void apply(Scene s,double[][] p){for(int i=0;i<p.length;i++){Scene.Vertex v=s.nodes.get(i);v.x=p[i][0];v.y=p[i][1];v.z=p[i][2];}}
    void generate(String template,int count,int groups,double average,long seed){
        if(!Set.of("community","layered","hub").contains(template))throw new IllegalArgumentException("template must be community, layered or hub");
        if(count<2||count>10000||groups<1||groups>count||average<0||average>count-1)throw new IllegalArgumentException("Invalid generation parameters");
        cancel.set(true);ProjectController pc=projects();if(!pc.hasCurrentProject())pc.newProject();else pc.openWorkspace(pc.newWorkspace(pc.getCurrentProject()));
        Workspace w=workspace();pc.renameWorkspace(w,"3D 示例 · "+template+" · seed "+seed);
        GraphModel gm=model(w);Graph graph=gm.getGraph();Random random=new Random(seed);Column community=gm.getNodeTable().addColumn("community",String.class);Column synthetic=gm.getNodeTable().addColumn("synthetic",Boolean.class);List<Node> nodes=new ArrayList<>();
        graph.writeLock();try{
            for(int i=0;i<count;i++){Node n=gm.factory().newNode("n"+(i+1));int c=i%groups;n.setLabel("节点 "+(i+1));n.setAttribute(community,"群体 "+(c+1));n.setAttribute(synthetic,true);n.setColor(Color.getHSBColor((float)c/groups,.65f,.9f));n.setSize(8);n.setX((random.nextFloat()-.5f)*500);n.setY((random.nextFloat()-.5f)*500);graph.addNode(n);nodes.add(n);}
            int target=(int)Math.round(count*average/2);Set<Long> used=new HashSet<>();int attempts=0;
            while(used.size()<target && attempts++<Math.max(1000,target*80)){
                int a=random.nextInt(count),b=random.nextInt(count);if(a==b)continue;
                if(template.equals("hub") && random.nextDouble()<.8)a=random.nextInt(Math.max(1,Math.min(groups,count/4)));
                if(a==b)continue;
                if(template.equals("community") && a%groups!=b%groups && random.nextDouble()<.85)continue;
                if(template.equals("layered")){if(a%groups==b%groups)continue;if(a%groups>b%groups){int t=a;a=b;b=t;}}
                else if(a>b){int t=a;a=b;b=t;}
                long key=(long)a*count+b;if(used.add(key))graph.addEdge(gm.factory().newEdge(nodes.get(a),nodes.get(b),template.equals("layered")));
            }
        }finally{graph.writeUnlock();}
        Scene s=scene();s.seed=seed;s.nodes.clear();s.synthetic=true;s.template=template;s.source="Synthetic example, not empirical observations; requested average degree="+average;sync(s,w);
        for(Scene.Vertex v:s.nodes){int c=Integer.parseInt(v.id.substring(1))-1;double angle=(double)(c%groups)/groups*Math.PI*2;v.x+=Math.cos(angle)*250;v.y+=Math.sin(angle)*250;v.size=5+Math.sqrt(v.degree)*2;}
        if(template.equals("layered"))s.layer("community",160);s.fit();
    }
    static String str(JsonObject j,String k,String d){return j.has(k)&&!j.get(k).isJsonNull()?j.get(k).getAsString():d;}
    static double num(JsonObject j,String k,double d){double n=j.has(k)?j.get(k).getAsDouble():d;if(!Double.isFinite(n))throw new IllegalArgumentException("Non-finite "+k);return n;}
    static int integer(JsonObject j,String k,int d){return (int)num(j,k,d);}
    static boolean bool(JsonObject j,String k,boolean d){return j.has(k)?j.get(k).getAsBoolean():d;}
    static List<String> strings(JsonObject j,String k){List<String> a=new ArrayList<>();if(j.has(k))for(JsonElement e:j.getAsJsonArray(k))a.add(e.getAsString());return a;}
}
