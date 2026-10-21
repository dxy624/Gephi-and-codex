package org.gephi.plugins.explorer3d;

import com.jogamp.opengl.*;
import com.jogamp.opengl.util.awt.TextRenderer;
import com.jogamp.common.nio.Buffers;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.awt.Font;
import java.nio.*;
import java.util.function.Supplier;

/** Batched OpenGL edges and shaded point-sphere nodes with a real depth buffer. */
public final class GraphRenderer implements GLEventListener {
    final Supplier<Scene> source;int program,sizeLocation;TextRenderer text;
    public int width=1,height=1;public long frames;long start=System.nanoTime();public double fps;
    public GraphRenderer(Supplier<Scene> source){this.source=source;}
    public void init(GLAutoDrawable d){
        GL2 gl=d.getGL().getGL2();
        String vs="#version 120\nattribute float nodeSize; uniform float scale; uniform float perspective; void main(){vec4 p=gl_ModelViewMatrix*gl_Vertex;gl_Position=gl_ProjectionMatrix*p;gl_FrontColor=gl_Color;gl_PointSize=clamp(nodeSize*scale*(perspective>0.5?1000.0/max(20.0,-p.z):1.0),3.0,120.0);}";
        String fs="#version 120\nvoid main(){vec2 xy=gl_PointCoord*2.0-1.0;float r=dot(xy,xy);if(r>1.0)discard;vec3 normal=vec3(xy,sqrt(1.0-r));float light=0.35+0.65*max(0.0,dot(normal,normalize(vec3(-0.4,0.6,1.0))));gl_FragColor=vec4(gl_Color.rgb*light,gl_Color.a);gl_FragDepth=gl_FragCoord.z-r*0.00001;}";
        program=gl.glCreateProgram();
        for(int i=0;i<2;i++){int shader=gl.glCreateShader(i==0?GL2.GL_VERTEX_SHADER:GL2.GL_FRAGMENT_SHADER);String[] src={i==0?vs:fs};gl.glShaderSource(shader,1,src,null,0);gl.glCompileShader(shader);int[] ok=new int[1];gl.glGetShaderiv(shader,GL2.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new GLException("3D shader compilation failed");gl.glAttachShader(program,shader);gl.glDeleteShader(shader);}
        gl.glLinkProgram(program);int[] ok=new int[1];gl.glGetProgramiv(program,GL2.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new GLException("3D shader linking failed");
        sizeLocation=gl.glGetAttribLocation(program,"nodeSize");text=new TextRenderer(new Font("SansSerif",Font.PLAIN,14),true,true);
        ExplorerPanel.rendererStatus=gl.glGetString(GL.GL_RENDERER)+" / "+gl.glGetString(GL.GL_VERSION);
    }
    public static Matrix4f matrix(Scene s,int width,int height){
        float aspect=(float)width/Math.max(1,height);Matrix4f p=new Matrix4f();
        if(s.perspective)p.perspective((float)Math.toRadians(45),aspect,1,200000);
        else{float h=(float)s.distance*.4142f;p.ortho(-h*aspect,h*aspect,-h,h,1,200000);}
        return p.mul(view(s));
    }
    static Matrix4f view(Scene s){return new Matrix4f().translate(0,0,(float)-s.distance).rotateX((float)s.pitch).rotateY((float)s.yaw).translate((float)-s.targetX,(float)-s.targetY,(float)-s.targetZ);}
    public static float[] project(Scene s,Scene.Vertex n,int w,int h){Vector4f p=new Vector4f((float)n.x,(float)n.y,(float)n.z,1).mul(matrix(s,w,h));if(p.w<=0)return null;return new float[]{(p.x/p.w+1)*w/2,(1-p.y/p.w)*h/2,p.z/p.w};}
    public void display(GLAutoDrawable drawable){
        Scene s=source.get();GL2 gl=drawable.getGL().getGL2();width=drawable.getSurfaceWidth();height=drawable.getSurfaceHeight();
        gl.glViewport(0,0,width,height);if(s==null||s.dark)gl.glClearColor(.035f,.055f,.09f,1);else gl.glClearColor(1,1,1,1);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT|GL.GL_DEPTH_BUFFER_BIT);if(s==null)return;
        gl.glEnable(GL.GL_DEPTH_TEST);gl.glDepthFunc(GL.GL_LEQUAL);gl.glEnable(GL.GL_BLEND);gl.glBlendFunc(GL.GL_SRC_ALPHA,GL.GL_ONE_MINUS_SRC_ALPHA);
        float[] mat=new float[16];float aspect=(float)width/Math.max(1,height);Matrix4f projection=new Matrix4f();if(s.perspective)projection.perspective((float)Math.toRadians(45),aspect,1,200000);else{float h=(float)s.distance*.4142f;projection.ortho(-h*aspect,h*aspect,-h,h,1,200000);}
        gl.glMatrixMode(GL2.GL_PROJECTION);gl.glLoadMatrixf(projection.get(mat),0);gl.glMatrixMode(GL2.GL_MODELVIEW);gl.glLoadMatrixf(view(s).get(mat),0);
        int nn=s.nodes.size(),en=s.edges.size();FloatBuffer positions=Buffers.newDirectFloatBuffer(Math.max(3,en*6)),colors=Buffers.newDirectFloatBuffer(Math.max(4,en*8));
        int lines=0;
        for(Scene.Link e:s.edges){Scene.Vertex a=s.nodes.get(e.source),b=s.nodes.get(e.target);if(!s.visible(a)||!s.visible(b)||e.weight<s.minWeight)continue;boolean hot=s.pathEdges.isEmpty()?(!s.highlight.isEmpty()&&s.highlight.contains(a.id)&&s.highlight.contains(b.id)):s.pathEdges.contains(e.id);float alpha=hot?.85f:s.highlight.isEmpty()?.25f:.035f;
            for(Scene.Vertex v:new Scene.Vertex[]{a,b}){positions.put((float)v.x).put((float)v.y).put((float)v.z);colors.put(hot?1f:s.dark?.5f:.35f).put(hot?.68f:s.dark?.65f:.4f).put(hot?.2f:s.dark?.8f:.45f).put(alpha);}lines+=2;}
        positions.flip();colors.flip();gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);gl.glEnableClientState(GL2.GL_COLOR_ARRAY);gl.glVertexPointer(3,GL.GL_FLOAT,0,positions);gl.glColorPointer(4,GL.GL_FLOAT,0,colors);gl.glLineWidth((float)s.edgeWidth);gl.glDepthMask(false);gl.glDrawArrays(GL.GL_LINES,0,lines);gl.glDepthMask(true);
        positions=Buffers.newDirectFloatBuffer(Math.max(3,nn*3));colors=Buffers.newDirectFloatBuffer(Math.max(4,nn*4));FloatBuffer sizes=Buffers.newDirectFloatBuffer(Math.max(1,nn));int points=0;
        for(Scene.Vertex v:s.nodes)if(s.visible(v)){positions.put((float)v.x).put((float)v.y).put((float)v.z);boolean selected=s.selection.contains(v.id);colors.put(selected?1f:v.r).put(selected?.82f:v.g).put(selected?.3f:v.b).put(s.highlight.isEmpty()||s.emphasized(v)?1:.15f);sizes.put((float)(v.size*(selected?1.5:1)));points++;}
        positions.flip();colors.flip();sizes.flip();gl.glVertexPointer(3,GL.GL_FLOAT,0,positions);gl.glColorPointer(4,GL.GL_FLOAT,0,colors);
        gl.glUseProgram(program);gl.glUniform1f(gl.glGetUniformLocation(program,"scale"),(float)(s.nodeScale*height/900.0));gl.glUniform1f(gl.glGetUniformLocation(program,"perspective"),s.perspective?1:0);gl.glEnable(GL2.GL_POINT_SPRITE);gl.glEnable(GL2.GL_VERTEX_PROGRAM_POINT_SIZE);gl.glEnableVertexAttribArray(sizeLocation);gl.glVertexAttribPointer(sizeLocation,1,GL.GL_FLOAT,false,0,sizes);gl.glDrawArrays(GL.GL_POINTS,0,points);gl.glDisableVertexAttribArray(sizeLocation);gl.glUseProgram(0);gl.glDisable(GL2.GL_POINT_SPRITE);gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);gl.glDisableClientState(GL2.GL_COLOR_ARRAY);
        gl.glDisable(GL.GL_DEPTH_TEST);
        text.beginRendering(width,height);text.setColor(s.dark?.9f:.12f,s.dark?.94f:.15f,s.dark?1:.2f,1);
        if(s.labels){int shown=0;for(Scene.Vertex v:s.nodes)if(s.visible(v)&&(s.emphasized(v)||s.nodes.size()<=100||v.degree>=15)&&shown<80){float[] p=project(s,v,width,height);if(p!=null&&p[2]>=-1&&p[2]<=1){text.draw(v.label,(int)p[0]+8,height-(int)p[1]);shown++;}}}
        text.draw((s.synthetic?"SYNTHETIC / 示例网络   ":"")+s.nodes.size()+" nodes · "+s.edges.size()+" edges",16,18);text.endRendering();
        frames++;long now=System.nanoTime();if(now-start>1_000_000_000L){fps=frames*1e9/(now-start);frames=0;start=now;}
    }
    public void reshape(GLAutoDrawable d,int x,int y,int w,int h){width=w;height=h;}
    public void dispose(GLAutoDrawable d){if(text!=null)text.dispose();if(program!=0)d.getGL().getGL2().glDeleteProgram(program);}
}
