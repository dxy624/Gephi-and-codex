package org.gephi.plugins.explorer3d;

import org.openide.windows.TopComponent;
import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import com.google.gson.*;

public final class ExplorerPanel extends TopComponent {
    private static ExplorerPanel instance;
    public static volatile String rendererStatus="not initialized";
    final Engine engine=Engine.INSTANCE;GLJPanel canvas;GraphRenderer renderer;
    final JLabel status=new JLabel("准备就绪");final JTextArea properties=new JTextArea();final JTextField search=new JTextField(14);
    public ExplorerPanel(){
        setName("3D Explorer");setDisplayName("3D Explorer · 三维探索");setLayout(new BorderLayout());
        JToolBar bar=new JToolBar();bar.setFloatable(false);bar.add(new JLabel(" 节点 "));search.setMaximumSize(new Dimension(150,28));bar.add(search);search.addActionListener(e->search());
        button(bar,"查找",this::search);button(bar,"读取当前图",()->act("refresh"));button(bar,"全景",()->act("focus"));
        button(bar,"三维布局",()->act("layout"));button(bar,"暂停",()->act("cancel_layout"));button(bar,"撤销",()->act("undo"));button(bar,"重做",()->act("redo"));
        add(bar,BorderLayout.NORTH);
        try{
            GLCapabilities caps=new GLCapabilities(GLProfile.get(GLProfile.GL2));caps.setDepthBits(24);caps.setDoubleBuffered(true);
            canvas=new GLJPanel(caps);renderer=new GraphRenderer(engine::scene);canvas.addGLEventListener(renderer);add(canvas,BorderLayout.CENTER);installMouse();
        }catch(Exception ex){rendererStatus=ex.toString();add(new JLabel("三维视图初始化失败："+ex.getMessage()),BorderLayout.CENTER);}
        JPanel side=new JPanel(new BorderLayout(4,6));side.setPreferredSize(new Dimension(220,400));side.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        properties.setEditable(false);properties.setLineWrap(true);properties.setWrapStyleWord(true);side.add(new JScrollPane(properties),BorderLayout.CENTER);
        JPanel controls=new JPanel(new GridLayout(0,1,4,5));
        button(controls,"一阶邻居",()->selectedNeighbor(1));button(controls,"两阶邻居",()->selectedNeighbor(2));button(controls,"两节点最短路径",()->{Scene s=engine.scene();if(s==null||s.selection.size()!=2){status.setText("请按 Ctrl 选择两个节点");return;}String[] ids=s.selection.toArray(String[]::new);act("path","source",ids[0],"target",ids[1],"directed",true);});
        button(controls,"清除高亮",()->act("clear_highlight"));button(controls,"按社区分层",()->act("layer","attribute","community"));button(controls,"固定选中节点",()->selectionAct("pin","pinned",true));button(controls,"解除固定",()->selectionAct("pin","pinned",false));
        button(controls,"透视 / 正交",()->{Scene s=engine.scene();if(s!=null)act("camera","perspective",!s.perspective);});
        button(controls,"保存视角",()->{String n=JOptionPane.showInputDialog(this,"视角名称","观察视角");if(n!=null&&!n.isBlank())act("bookmark","name",n);});
        button(controls,"恢复视角",()->{Scene s=engine.scene();if(s==null)return;Object n=JOptionPane.showInputDialog(this,"选择视角","视角",JOptionPane.PLAIN_MESSAGE,null,s.bookmarks.keySet().toArray(),null);if(n!=null)act("bookmark","name",n.toString(),"restore",true);});
        button(controls,"生成示例网络",()->{if(JOptionPane.showConfirmDialog(this,"在新工作区生成 60 节点示例网络？","示例网络",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)act("generate");});
        button(controls,"导出 PNG",()->{JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("network-3d.png"));if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION)act("export_png","path",chooser.getSelectedFile().getAbsolutePath());});
        side.add(controls,BorderLayout.SOUTH);add(side,BorderLayout.EAST);
        status.setBorder(BorderFactory.createEmptyBorder(5,12,5,10));add(status,BorderLayout.SOUTH);
        engine.changed=this::refresh;refresh();
    }
    public int getPersistenceType(){return PERSISTENCE_NEVER;}
    public static void showPanel(){showPanel(false);}
    public static void showPanel(boolean activate){if(instance==null)instance=new ExplorerPanel();instance.open();if(activate)instance.requestActive();else instance.requestVisible();}
    void button(JComponent p,String label,Runnable r){JButton b=new JButton(label);b.addActionListener(e->{try{r.run();}catch(Exception ex){status.setText(ex.getMessage());}});p.add(b);}
    void act(String op,Object... args){JsonObject j=new JsonObject();j.addProperty("operation",op);Scene s=engine.scene();if(s!=null)j.addProperty("workspace_id",s.workspaceId);for(int i=0;i<args.length;i+=2)j.add(args[i].toString(),Scene.JSON.toJsonTree(args[i+1]));try{engine.command(j);}catch(Exception ex){status.setText("操作失败："+ex.getMessage());}}
    void selectionAct(String op,Object... args){Scene s=engine.scene();if(s==null)return;Object[] a=new Object[args.length+2];a[0]="ids";a[1]=new ArrayList<>(s.selection);System.arraycopy(args,0,a,2,args.length);act(op,a);}
    void selectedNeighbor(int hops){Scene s=engine.scene();if(s==null||s.selection.isEmpty())return;act("neighbors","id",s.selection.iterator().next(),"hops",hops);}
    void search(){Scene s=engine.scene();if(s==null)return;String q=search.getText().trim().toLowerCase(Locale.ROOT);if(q.isEmpty())return;for(Scene.Vertex n:s.nodes)if(n.label.toLowerCase(Locale.ROOT).contains(q)||n.id.toLowerCase(Locale.ROOT).contains(q)){act("select","ids",List.of(n.id));act("focus","ids",List.of(n.id));return;}status.setText("未找到节点");}
    public void refresh(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
        Scene s=engine.scene();if(s==null){status.setText("打开一个网络，或生成示例。左拖旋转，右拖平移，滚轮缩放，Ctrl 多选。");properties.setText("Gephi 3D Explorer 0.1 alpha\n\n三维视图独立保存位置。\nAPI: 127.0.0.1:"+ApiServer.port());}
        else{status.setText(s.nodes.size()+" 节点 / "+s.edges.size()+" 边  · 布局 "+engine.jobState+" #"+engine.iteration+" · 左拖旋转 · 右拖平移 · 滚轮缩放 · Ctrl 多选");StringBuilder p=new StringBuilder(s.synthetic?"【合成示例，非真实观测】\n\n":"");for(String id:s.selection){try{Scene.Vertex n=s.find(id);p.append(n.label).append("\nID: ").append(n.id).append("\n度数: ").append(n.degree).append("\n固定: ").append(n.pinned).append("\n");n.attributes.forEach((k,v)->p.append(k).append(": ").append(v).append("\n"));p.append("\n");}catch(IllegalArgumentException ignore){}}properties.setText(p.toString());}
        if(canvas!=null)canvas.repaint();
    }
    void installMouse(){MouseAdapter mouse=new MouseAdapter(){Point last,down;int button;
        public void mousePressed(MouseEvent e){last=e.getPoint();down=last;button=e.getButton();}
        public void mouseDragged(MouseEvent e){Scene s=engine.scene();if(s==null||last==null)return;int dx=e.getX()-last.x,dy=e.getY()-last.y;
            if(button==MouseEvent.BUTTON3||button==MouseEvent.BUTTON2){double scale=s.distance/Math.max(1,canvas.getHeight());double cy=Math.cos(s.yaw),sy=Math.sin(s.yaw);s.targetX-=dx*scale*cy;s.targetZ-=dx*scale*sy;s.targetY+=dy*scale;}
            else if(!e.isShiftDown()){s.yaw+=dx*.009;s.pitch=Math.max(-1.5,Math.min(1.5,s.pitch+dy*.009));}s.revision++;last=e.getPoint();canvas.repaint();}
        public void mouseReleased(MouseEvent e){Scene s=engine.scene();if(s==null||down==null||button!=MouseEvent.BUTTON1)return;
            if(e.isShiftDown()&&down.distance(e.getPoint())>5){if(!e.isControlDown())s.selection.clear();Rectangle box=new Rectangle(Math.min(down.x,e.getX()),Math.min(down.y,e.getY()),Math.abs(down.x-e.getX()),Math.abs(down.y-e.getY()));for(Scene.Vertex n:s.nodes)if(s.visible(n)){float[] p=GraphRenderer.project(s,n,canvas.getWidth(),canvas.getHeight());if(p!=null&&p[2]>=-1&&p[2]<=1&&box.contains(p[0],p[1]))s.selection.add(n.id);}s.revision++;refresh();return;}
            if(down.distance(e.getPoint())>5)return;Scene.Vertex hit=null;double best=22;float bestZ=Float.MAX_VALUE;
            for(Scene.Vertex n:s.nodes)if(s.visible(n)){float[] p=GraphRenderer.project(s,n,canvas.getWidth(),canvas.getHeight());if(p==null||p[2]<-1||p[2]>1)continue;double dist=Math.hypot(p[0]-e.getX(),p[1]-e.getY());if(dist<best && p[2]<bestZ){hit=n;bestZ=p[2];}}
            if(!e.isControlDown())s.selection.clear();if(hit!=null){if(e.isControlDown()&&s.selection.contains(hit.id))s.selection.remove(hit.id);else s.selection.add(hit.id);if(e.getClickCount()==2)act("focus","ids",List.of(hit.id));}s.revision++;refresh();}
        public void mouseWheelMoved(MouseWheelEvent e){Scene s=engine.scene();if(s==null)return;s.distance=Math.max(20,Math.min(100000,s.distance*Math.pow(1.12,e.getPreciseWheelRotation())));s.revision++;canvas.repaint();}
        public void mouseMoved(MouseEvent e){Scene s=engine.scene();if(s==null)return;Scene.Vertex hit=null;double best=16;for(Scene.Vertex n:s.nodes)if(s.visible(n)){float[] p=GraphRenderer.project(s,n,canvas.getWidth(),canvas.getHeight());if(p!=null&&p[2]>=-1&&p[2]<=1){double d=Math.hypot(p[0]-e.getX(),p[1]-e.getY());if(d<best){hit=n;best=d;}}}canvas.setToolTipText(hit==null?null:hit.label+" · "+hit.id+" · 度数 "+hit.degree+" · "+hit.community);}
    };canvas.addMouseListener(mouse);canvas.addMouseMotionListener(mouse);canvas.addMouseWheelListener(mouse);}
    public static Map<String,Object> benchmark(){
        Scene snapshot=Engine.INSTANCE.requireScene().copy();GLCapabilities caps=new GLCapabilities(GLProfile.get(GLProfile.GL2));caps.setDepthBits(24);caps.setFBO(true);caps.setDoubleBuffered(false);
        GLOffscreenAutoDrawable d=GLDrawableFactory.getFactory(caps.getGLProfile()).createOffscreenAutoDrawable(null,caps,null,1920,1080);d.addGLEventListener(new GraphRenderer(()->snapshot));
        try{for(int i=0;i<5;i++)d.display();long start=System.nanoTime();int frames=0;while(frames<90 && System.nanoTime()-start<8_000_000_000L){snapshot.yaw+=.02;d.display();d.invoke(true,g->{g.getGL().glFinish();return true;});frames++;}double seconds=(System.nanoTime()-start)/1e9;return Map.of("success",true,"width",1920,"height",1080,"frames",frames,"seconds",seconds,"fps",frames/seconds,"nodes",snapshot.nodes.size(),"edges",snapshot.edges.size(),"renderer",rendererStatus);}
        finally{d.destroy();}
    }
    public static Map<String,Object> exportPng(String path,int width,int height){
        if(width<200||height<200||width>8000||height>8000||((long)width*height)>40_000_000)throw new IllegalArgumentException("Export dimensions must be 200..8000 and <=40 megapixels");
        Path output=Path.of(path);if(!output.isAbsolute()||!path.toLowerCase(Locale.ROOT).endsWith(".png"))throw new IllegalArgumentException("Absolute .png path required");if(Files.exists(output))throw new IllegalArgumentException("Output exists; choose a new file name");
        Scene snapshot=Engine.INSTANCE.requireScene().copy();GLCapabilities caps=new GLCapabilities(GLProfile.get(GLProfile.GL2));caps.setDepthBits(24);caps.setFBO(true);caps.setDoubleBuffered(false);
        GLOffscreenAutoDrawable d=GLDrawableFactory.getFactory(caps.getGLProfile()).createOffscreenAutoDrawable(null,caps,null,width,height);d.addGLEventListener(new GraphRenderer(()->snapshot));final BufferedImage[] image={null};
        try{d.display();d.invoke(true,drawable->{image[0]=new AWTGLReadBufferUtil(drawable.getGLProfile(),false).readPixelsToBufferedImage(drawable.getGL(),true);return true;});if(image[0]==null)throw new IllegalStateException("No framebuffer image");ImageIO.write(image[0],"png",output.toFile());Files.writeString(Path.of(path+".scene.json"),Scene.JSON.toJson(snapshot));}
        catch(Exception ex){throw new IllegalStateException("Export failed: "+ex.getMessage(),ex);}finally{d.destroy();}
        return Map.of("success",true,"path",path,"width",width,"height",height,"workspace_id",snapshot.workspaceId,"revision",snapshot.revision);
    }
}
