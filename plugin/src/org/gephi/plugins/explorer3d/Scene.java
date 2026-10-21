package org.gephi.plugins.explorer3d;

import java.util.*;
import com.google.gson.Gson;

/** Workspace-owned, JSON-serializable view state; never changes Gephi XY positions. */
public final class Scene {
    public static final Gson JSON = new Gson();
    public int schema = 1, workspaceId;
    public String source = "current workspace", template = "imported";
    public long revision, seed = 42;
    public boolean synthetic, dark = true, labels = true, isolate;
    public double yaw = .5, pitch = .3, distance = 1200, targetX, targetY, targetZ;
    public boolean perspective = true;
    public double nodeScale = 1, edgeWidth = 1, minWeight = -Double.MAX_VALUE;
    public int minDegree;
    public String communityFilter = "", layerAttribute = "";
    public double layerSpacing = 160;
    public List<Vertex> nodes = new ArrayList<>();
    public List<Link> edges = new ArrayList<>();
    public Set<String> selection = new LinkedHashSet<>(), highlight = new LinkedHashSet<>();
    public Set<String> pathEdges = new HashSet<>();
    public Map<String, double[]> bookmarks = new LinkedHashMap<>();
    public static final class Vertex {
        public String id, label, community = "";
        public double x,y,z, size=8;
        public int degree;
        public boolean pinned;
        public float r=.22f,g=.65f,b=.9f;
        public Map<String,String> attributes = new LinkedHashMap<>();
    }
    public static final class Link {
        public String id;
        public int source,target;
        public double weight=1;
        public boolean directed;
    }
    public Scene copy() { return JSON.fromJson(JSON.toJson(this),Scene.class); }
    public Vertex find(String id) { for(Vertex n:nodes) if(n.id.equals(id)) return n; throw new IllegalArgumentException("Unknown node: "+id); }
    public boolean visible(Vertex n) { return n.degree>=minDegree && (communityFilter.isEmpty() || n.community.equals(communityFilter)) && (!isolate || highlight.isEmpty() || highlight.contains(n.id)); }
    public boolean emphasized(Vertex n) { return selection.contains(n.id) || highlight.contains(n.id); }
    public Map<String,Object> status() {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("workspace_id",workspaceId);m.put("revision",revision);m.put("nodes",nodes.size());m.put("edges",edges.size());
        m.put("visible_nodes",nodes.stream().filter(this::visible).count());
        m.put("selection",selection);m.put("highlight",highlight);m.put("synthetic",synthetic);m.put("source",source);m.put("template",template);m.put("seed",seed);
        m.put("camera",Map.of("yaw",yaw,"pitch",pitch,"distance",distance,"target",new double[]{targetX,targetY,targetZ},"perspective",perspective));
        m.put("bookmarks",bookmarks.keySet());m.put("layer_attribute",layerAttribute);
        return m;
    }
    public void fit() {
        if(nodes.isEmpty()) return;
        targetX=nodes.stream().mapToDouble(n->n.x).average().orElse(0);
        targetY=nodes.stream().mapToDouble(n->n.y).average().orElse(0);
        targetZ=nodes.stream().mapToDouble(n->n.z).average().orElse(0);
        double radius=10;
        for(Vertex n:nodes) radius=Math.max(radius,Math.sqrt(sq(n.x-targetX)+sq(n.y-targetY)+sq(n.z-targetZ)));
        distance=radius*3;
    }
    static double sq(double x){return x*x;}
    public List<List<Integer>> adjacency(boolean directed) {
        List<List<Integer>> a=new ArrayList<>(); for(Vertex n:nodes)a.add(new ArrayList<>());
        for(Link e:edges){a.get(e.source).add(e.target);if(!directed || !e.directed)a.get(e.target).add(e.source);}
        return a;
    }
    public void neighbors(String id,int hops) {
        if(hops<1 || hops>20)throw new IllegalArgumentException("hops must be 1..20");
        Vertex start=find(id);List<List<Integer>> adj=adjacency(false);Set<Integer> seen=new LinkedHashSet<>();seen.add(nodes.indexOf(start));
        Set<Integer> frontier=new LinkedHashSet<>(seen);
        for(int h=0;h<hops;h++){Set<Integer> next=new LinkedHashSet<>();for(int i:frontier)for(int j:adj.get(i))if(seen.add(j))next.add(j);frontier=next;}
        highlight.clear();pathEdges.clear();for(int i:seen)highlight.add(nodes.get(i).id);
    }
    public List<String> shortestPath(String from,String to,boolean directed) {
        int s=nodes.indexOf(find(from)),t=nodes.indexOf(find(to));List<List<Integer>> adj=adjacency(directed);
        int[] prev=new int[nodes.size()];Arrays.fill(prev,-1);prev[s]=s;ArrayDeque<Integer> q=new ArrayDeque<>();q.add(s);
        while(!q.isEmpty() && prev[t]<0){int i=q.remove();for(int j:adj.get(i))if(prev[j]<0){prev[j]=i;q.add(j);}}
        List<String> path=new ArrayList<>();highlight.clear();pathEdges.clear();if(prev[t]<0)return path;
        List<Integer> indices=new ArrayList<>();for(int p=t;;p=prev[p]){indices.add(p);if(p==s)break;}Collections.reverse(indices);
        for(int i:indices){String id=nodes.get(i).id;path.add(id);highlight.add(id);}
        for(int p=1;p<indices.size();p++){int a=indices.get(p-1),b=indices.get(p);for(Link e:edges)if((e.source==a && e.target==b)||((!directed||!e.directed)&&e.source==b&&e.target==a))pathEdges.add(e.id);}
        return path;
    }
    public void layer(String attribute,double spacing) {
        if(!Double.isFinite(spacing)||spacing<1||spacing>10000)throw new IllegalArgumentException("spacing must be 1..10000");
        SortedSet<String> values=new TreeSet<>();
        for(Vertex n:nodes)values.add(attribute.equals("community")?n.community:n.attributes.getOrDefault(attribute,"(missing)"));
        List<String> order=new ArrayList<>(values);
        try { order.sort(Comparator.comparingDouble(Double::parseDouble)); } catch(NumberFormatException ex){Collections.sort(order);}
        for(Vertex n:nodes)if(!n.pinned)n.z=(order.indexOf(attribute.equals("community")?n.community:n.attributes.getOrDefault(attribute,"(missing)"))-(order.size()-1)/2.0)*spacing;
        layerAttribute=attribute;layerSpacing=spacing;
    }
}
