package org.gephi.plugins.explorer3d;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.gephi.io.importer.api.*;
import org.gephi.io.processor.plugin.DefaultProcessor;
import org.gephi.project.api.*;
import org.openide.util.Lookup;

/** Parse outside the EDT, then commit to a fresh workspace without desktop input. */
final class GraphImport {
    final Path path;
    final Container container;
    final int nodes, edges;
    final List<String> warnings;
    final Map<String,double[]> positions;
    final Set<String> zeroWeights;
    final Set<String> explicitColors;

    private GraphImport(Path path, Container container, int nodes, int edges,
                        List<String> warnings, Map<String,double[]> positions, Set<String> zeroWeights, Set<String> explicitColors) {
        this.path=path;this.container=container;this.nodes=nodes;this.edges=edges;
        this.warnings=warnings;this.positions=positions;
        this.zeroWeights=zeroWeights;
        this.explicitColors=explicitColors;
    }

    static GraphImport read(String name) throws Exception {
        Path path=Path.of(name);
        if(!path.isAbsolute() || !name.toLowerCase(Locale.ROOT).endsWith(".gexf"))
            throw new IllegalArgumentException("Import requires an absolute .gexf file path; convert CSV/XLSX to GEXF first");
        if(!Files.isRegularFile(path))throw new IllegalArgumentException("GEXF file not found: "+path);
        byte[] bytes;
        try(var input=Files.newInputStream(path)){bytes=input.readNBytes(20_000_001);}
        if(bytes.length==0 || bytes.length>20_000_000)throw new IllegalArgumentException("GEXF must be nonempty and at most 20 MB");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new DefaultHandler(){
            public void error(SAXParseException ex) throws SAXParseException{throw ex;}
            public void fatalError(SAXParseException ex) throws SAXParseException{throw ex;}
        });
        var document=builder.parse(new ByteArrayInputStream(bytes));
        if(!"gexf".equals(document.getDocumentElement().getLocalName()))throw new IllegalArgumentException("Expected a GEXF document");
        NodeList graphs=document.getElementsByTagNameNS("*","graph");
        if(graphs.getLength()!=1)throw new IllegalArgumentException("Exactly one GEXF graph is required");
        Element graph=(Element)graphs.item(0);
        if(graph.hasAttribute("mode") && !graph.getAttribute("mode").equals("static"))throw new IllegalArgumentException("Only static GEXF graphs are supported");
        NodeList nodeList=graph.getElementsByTagNameNS("*","node"),edgeList=graph.getElementsByTagNameNS("*","edge");
        int nodes=nodeList.getLength(),edges=edgeList.getLength();
        if(nodes<1 || nodes>10000 || edges>100000)throw new IllegalArgumentException("Import supports 1..10000 nodes and at most 100000 edges");
        Set<String> ids=new HashSet<>(),edgeIds=new HashSet<>(),explicitColors=new HashSet<>();Map<String,double[]> positions=new HashMap<>();
        for(int i=0;i<nodes;i++){
            Element node=(Element)nodeList.item(i);String id=node.getAttribute("id");
            if(id.isEmpty() || !ids.add(id))throw new IllegalArgumentException("Missing or duplicate node ID: "+id);
            if(node.getElementsByTagNameNS("*","nodes").getLength()>0)throw new IllegalArgumentException("Nested GEXF nodes are not supported; use flat nodes and parent attributes");
            if(node.getElementsByTagNameNS("*","color").getLength()>0)explicitColors.add(id);
            NodeList locations=node.getElementsByTagNameNS("*","position");
            if(locations.getLength()>0){Element p=(Element)locations.item(0);double[] xyz=new double[3];String[] axes={"x","y","z"};
                for(int a=0;a<3;a++){xyz[a]=p.hasAttribute(axes[a])?Double.parseDouble(p.getAttribute(axes[a])):0;if(!Double.isFinite(xyz[a]) || Math.abs(xyz[a])>1e9)throw new IllegalArgumentException("Invalid node position: "+id);}
                positions.put(id,xyz);
            }
        }
        for(int i=0;i<edges;i++){
            Element edge=(Element)edgeList.item(i);
            if(!ids.contains(edge.getAttribute("source")) || !ids.contains(edge.getAttribute("target")))throw new IllegalArgumentException("Edge endpoint is not a declared node");
            if(edge.hasAttribute("id") && !edgeIds.add(edge.getAttribute("id")))throw new IllegalArgumentException("Duplicate edge ID");
            if(edge.hasAttribute("weight") && !Double.isFinite(Double.parseDouble(edge.getAttribute("weight"))))throw new IllegalArgumentException("Non-finite edge weight");
        }
        ImportController controller=Lookup.getDefault().lookup(ImportController.class);
        if(controller==null)throw new IllegalStateException("Gephi import service is unavailable");
        var importer=controller.getFileImporter("gexf");
        if(importer==null)throw new IllegalStateException("Gephi GEXF importer is unavailable");
        Container container=controller.importFile(new ByteArrayInputStream(bytes),importer);
        if(container==null)throw new IllegalArgumentException("Gephi rejected the GEXF graph");
        // Gephi's container verifier deletes zero-weight edges. Keep them through
        // verification/processing, then restore their exact weights in the new graph.
        Set<String> zeroWeights=new HashSet<>();
        for(EdgeDraft edge:container.getUnloader().getEdges())if(edge.getWeight()==0){zeroWeights.add(edge.getId());edge.setWeight(1);}
        container.getLoader().setElementIdType(ElementIdType.STRING);
        if(!container.verify())throw new IllegalArgumentException("Gephi rejected the GEXF graph");
        List<String> warnings=new ArrayList<>();
        for(Issue issue:container.getReport().getIssuesList(0)){
            if(issue.getLevel()==Issue.Level.SEVERE || issue.getLevel()==Issue.Level.CRITICAL)throw new IllegalArgumentException("GEXF import failed: "+issue.getMessage());
            if(issue.getLevel()==Issue.Level.WARNING && warnings.size()<20)warnings.add(issue.getMessage());
        }
        if(container.getUnloader().getNodeCount()!=nodes || container.getUnloader().getEdgeCount()!=edges)throw new IllegalArgumentException("GEXF import changed node/edge counts; graph was not committed");
        container.setSource(path.getFileName().toString());container.getLoader().setAutoScale(false);
        return new GraphImport(path,container,nodes,edges,warnings,positions,zeroWeights,explicitColors);
    }

    Object commit(Workspace previous, boolean synthetic) {
        Engine engine=Engine.INSTANCE;ProjectController pc=engine.projects();
        if(pc.getCurrentWorkspace()!=previous)throw new IllegalArgumentException("Workspace changed during import; read status and retry");
        if(engine.jobState.equals("running"))throw new IllegalArgumentException("Pause layout before importing");
        Project created=null;Workspace target=null;
        try{
            if(!pc.hasCurrentProject()){created=pc.newProject();target=pc.getCurrentWorkspace();}
            else target=pc.newWorkspace(pc.getCurrentProject());
            Lookup.getDefault().lookup(ImportController.class).process(container,new DefaultProcessor(),target);
            var imported=engine.model(target).getGraph();
            imported.writeLock();try{
                for(String id:zeroWeights){var edge=imported.getEdge(id);if(edge==null)throw new IllegalStateException("Zero-weight edge was lost during import");edge.setWeight(0);}
            }finally{imported.writeUnlock();}
            if(imported.getNodeCount()!=nodes || imported.getEdgeCount()!=edges)throw new IllegalStateException("Processed graph counts differ from the input");
            pc.renameWorkspace(target,"3D 导入 · "+path.getFileName());pc.setSource(target,path.toString());pc.openWorkspace(target);
            Scene scene=engine.scene();scene.synthetic=synthetic;scene.template="imported";scene.source="Imported GEXF: "+path.getFileName();
            for(Scene.Vertex node:scene.nodes){double[] xyz=positions.get(node.id);if(xyz!=null){node.x=xyz[0];node.y=xyz[1];node.z=xyz[2];}if(!explicitColors.contains(node.id)){node.r=.22f;node.g=.65f;node.b=.9f;}}
            scene.fit();scene.revision++;engine.changed.run();ExplorerPanel.showPanel();
            Map<String,Object> result=engine.state();result.put("import",Map.of("path",path.toString(),"nodes",nodes,"edges",edges,"new_workspace",true,"previous_workspace_id",previous==null?-1:previous.getId(),"warnings",warnings));return result;
        }catch(RuntimeException ex){
            if(previous!=null)pc.openWorkspace(previous);
            if(created!=null)pc.removeProject(created);else if(target!=null)pc.deleteWorkspace(target);
            throw ex;
        }
    }
}
