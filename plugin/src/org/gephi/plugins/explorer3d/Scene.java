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
    public String visualStyle = "classic";
    public boolean arrows;
    // Older map scenes always showed arrows. An explicit setting overrides that default.
    public boolean arrowsManuallySet;
    public double arrowScale = 1;
    GeoLayer geography;
    public boolean geographyEnabled;
    double[] regularCamera, geographicCamera;
    public double nodeScale = 1, edgeWidth = 1, minWeight = -Double.MAX_VALUE;
    public int minDegree;
    public String communityFilter = "", layerAttribute = "";
    public String nodeQuery = "", cityQuery = "", colorField = "", colorMode = "category";
    public int neighborhoodHops;
    public Set<String> neighborhoodRoots = new LinkedHashSet<>();
    public Set<String> hiddenLayers = new LinkedHashSet<>();
    public String legendPosition = "bottom";
    public Set<String> hiddenLegendItems = new LinkedHashSet<>();
    public Map<String,String> legendLabels = new LinkedHashMap<>();
    private transient Set<String> neighborhoodNodes;
    private transient NodeColors colors;
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
    private static final Gson COPY_JSON = new com.google.gson.GsonBuilder()
        .addSerializationExclusionStrategy(new com.google.gson.ExclusionStrategy() {
            public boolean shouldSkipField(com.google.gson.FieldAttributes field) {
                return field.getDeclaringClass() == Scene.class && field.getName().equals("geography");
            }
            public boolean shouldSkipClass(Class<?> type) { return false; }
        }).create();
    public Scene copy() {
        Scene copy = COPY_JSON.fromJson(COPY_JSON.toJson(this), Scene.class);
        copy.geography = geography;
        return copy;
    }
    public Vertex find(String id) { for(Vertex n:nodes) if(n.id.equals(id)) return n; throw new IllegalArgumentException("Unknown node: "+id); }
    public boolean visible(Vertex n) {
        return located(n) && passesFilters(n);
    }

    boolean located(Vertex node) {
        return !geographyEnabled || geography.positions.containsKey(node.id);
    }

    boolean passesFilters(Vertex node) {
        return node.degree >= minDegree && (communityFilter.isEmpty() || node.community.equals(communityFilter))
            && (layerAttribute.isEmpty() || !hiddenLayers.contains(fieldValue(node, layerAttribute)))
            && (nodeQuery.isEmpty() || contains(node.id, nodeQuery) || contains(node.label, nodeQuery)
                || contains(node.attributes.get("firm_code"), nodeQuery))
            && (cityQuery.isEmpty() || contains(node.attributes.get("city_code"), cityQuery)
                || contains(node.attributes.get("city_name"), cityQuery)
                || contains(node.attributes.get("reported_city_name"), cityQuery))
            && (!isolate || highlight.isEmpty() || highlight.contains(node.id))
            && (neighborhoodHops == 0 || neighborhood().contains(node.id));
    }

    boolean visible(Link edge) {
        return edge.weight >= minWeight && visible(nodes.get(edge.source)) && visible(nodes.get(edge.target));
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    void refreshViewCaches() {
        neighborhoodNodes = null;
        colors = null;
    }

    private Set<String> neighborhood() {
        if (neighborhoodNodes == null) {
            Set<Integer> roots = new LinkedHashSet<>();
            for (int i = 0; i < nodes.size(); i++) if (neighborhoodRoots.contains(nodes.get(i).id)) roots.add(i);
            Set<String> found = new HashSet<>();
            for (int i : reachable(roots, neighborhoodHops, minWeight)) found.add(nodes.get(i).id);
            neighborhoodNodes = found;
        }
        return neighborhoodNodes;
    }

    static String fieldValue(Vertex node, String field) {
        return switch (field) {
            case "community" -> node.community;
            case "degree" -> Integer.toString(node.degree);
            default -> node.attributes.getOrDefault(field, "");
        };
    }

    SortedSet<String> fields() {
        var fields = new TreeSet<>(Set.of("community", "degree"));
        for (Vertex node : nodes) fields.addAll(node.attributes.keySet());
        fields.remove("");
        return fields;
    }

    SortedSet<String> layers() {
        var values = new TreeSet<String>();
        if (!layerAttribute.isEmpty()) for (Vertex node : nodes) values.add(fieldValue(node, layerAttribute));
        return values;
    }

    NodeColors colors() {
        if (colors == null) colors = NodeColors.create(this);
        return colors;
    }
    public boolean emphasized(Vertex n) { return selection.contains(n.id) || highlight.contains(n.id); }
    public boolean showsArrows() { return arrows || (geographyEnabled && !arrowsManuallySet); }
    public Map<String,Object> status() {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("workspace_id",workspaceId);m.put("revision",revision);m.put("nodes",nodes.size());m.put("edges",edges.size());
        m.put("visible_nodes",nodes.stream().filter(this::visible).count());
        m.put("visible_edges",edges.stream().filter(this::visible).count());
        m.put("filters",Map.of("node_query",nodeQuery,"city_query",cityQuery,"community",communityFilter,
            "min_degree",minDegree,"min_weight",minWeight,"neighborhood_hops",neighborhoodHops,
            "neighbor_roots",neighborhoodRoots));
        m.put("hidden_layers", hiddenLayers);
        m.put("node_colors", Map.of("field", colorField, "mode", colorMode));
        m.put("paper_legend", Map.of("position", legendPosition, "hidden", hiddenLegendItems, "labels", legendLabels));
        m.put("selection",selection);m.put("highlight",highlight);m.put("synthetic",synthetic);m.put("source",source);m.put("template",template);m.put("seed",seed);
        m.put("camera",Map.of("yaw",yaw,"pitch",pitch,"distance",distance,"target",new double[]{targetX,targetY,targetZ},"perspective",perspective));
        m.put("bookmarks",bookmarks.keySet());m.put("layer_attribute",layerAttribute);
        m.put("style", Map.of("preset", visualStyle, "arrows", showsArrows(), "dark", dark, "labels", labels,
            "edge_width", edgeWidth, "arrow_scale", arrowScale));
        if (geography != null) m.put("geography", Map.of("enabled", geographyEnabled,
            "source", geography.source, "matched_nodes", nodes.stream().filter(n -> geography.positions.containsKey(n.id)).count(),
            "cities", geography.cities.size(), "leader_communities", geography.communities));
        return m;
    }
    public void fit() {
        if(nodes.isEmpty()) return;
        if (geographyEnabled) {
            double[] box = geography.bounds;
            targetX = (box[0] + box[2]) / 2;
            targetY = (box[1] + box[3]) / 2;
            targetZ = 0;
            distance = Math.max(box[2] - box[0], box[3] - box[1]) * 1.65;
            return;
        }
        targetX=nodes.stream().mapToDouble(n->n.x).average().orElse(0);
        targetY=nodes.stream().mapToDouble(n->n.y).average().orElse(0);
        targetZ=nodes.stream().mapToDouble(n->n.z).average().orElse(0);
        double radius=10;
        for(Vertex n:nodes) radius=Math.max(radius,Math.sqrt(sq(n.x-targetX)+sq(n.y-targetY)+sq(n.z-targetZ)));
        distance=radius*3;
    }
    double[] cameraValues() {
        return new double[]{yaw, pitch, distance, targetX, targetY, targetZ, perspective ? 1 : 0};
    }
    void restoreCamera(double[] view) {
        yaw = view[0]; pitch = view[1]; distance = view[2];
        targetX = view[3]; targetY = view[4]; targetZ = view[5]; perspective = view[6] > 0;
    }
    void toggleGeography(boolean enable) {
        if (enable == geographyEnabled) return;
        if (enable) {
            if (geography == null) throw new IllegalArgumentException("Load a city map for this workspace first");
            regularCamera = cameraValues();
            geographyEnabled = true;
            if (geographicCamera == null) {
                yaw = 0; pitch = 0; perspective = false;
                fit();
            } else restoreCamera(geographicCamera);
        } else {
            geographicCamera = cameraValues();
            geographyEnabled = false;
            if (regularCamera == null) fit();
            else restoreCamera(regularCamera);
        }
    }
    static double sq(double x){return x*x;}
    public List<List<Integer>> adjacency(boolean directed) {
        List<List<Integer>> a=new ArrayList<>(); for(Vertex n:nodes)a.add(new ArrayList<>());
        for(Link e:edges){a.get(e.source).add(e.target);if(!directed || !e.directed)a.get(e.target).add(e.source);}
        return a;
    }
    public void neighbors(String id,int hops) {
        if(hops<1 || hops>20)throw new IllegalArgumentException("hops must be 1..20");
        Vertex start=find(id);
        highlight.clear();pathEdges.clear();
        for(int i:reachable(Set.of(nodes.indexOf(start)), hops, -Double.MAX_VALUE)) highlight.add(nodes.get(i).id);
    }

    private Set<Integer> reachable(Set<Integer> roots, int hops, double threshold) {
        List<List<Integer>> adj=new ArrayList<>();
        for(Vertex node:nodes)adj.add(new ArrayList<>());
        for(Link edge:edges) if(edge.weight >= threshold){
            adj.get(edge.source).add(edge.target);
            adj.get(edge.target).add(edge.source);
        }
        Set<Integer> seen=new LinkedHashSet<>(roots);
        Set<Integer> frontier=roots;
        for(int h=0;h<hops;h++){Set<Integer> next=new LinkedHashSet<>();for(int i:frontier)for(int j:adj.get(i))if(seen.add(j))next.add(j);frontier=next;}
        return seen;
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
        for(Vertex n:nodes)values.add(fieldValue(n, attribute));
        List<String> order=new ArrayList<>(values);
        try { order.sort(Comparator.comparingDouble(Double::parseDouble)); } catch(NumberFormatException ex){Collections.sort(order);}
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < order.size(); i++) indices.put(order.get(i), i);
        for(Vertex n:nodes)if(!n.pinned)n.z=(indices.get(fieldValue(n, attribute))-(order.size()-1)/2.0)*spacing;
        if (!layerAttribute.equals(attribute)) hiddenLayers.clear();
        layerAttribute=attribute;layerSpacing=spacing;
    }
}
