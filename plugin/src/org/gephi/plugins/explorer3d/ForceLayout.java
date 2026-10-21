package org.gephi.plugins.explorer3d;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** 3D Fruchterman-Reingold. Octree repulsion, exact edge attraction, pinned nodes. */
public final class ForceLayout {
    static final class Octree {
        double x,y,z,half,mx,my,mz;int mass,index=-1,depth;Octree[] children;
        Octree(double x,double y,double z,double half,int depth){this.x=x;this.y=y;this.z=z;this.half=half;this.depth=depth;}
        void insert(int i,double[][] p){
            double[] v=p[i];mx=(mx*mass+v[0])/(mass+1);my=(my*mass+v[1])/(mass+1);mz=(mz*mass+v[2])/(mass+1);mass++;
            if(mass==1){index=i;return;}if(depth>=22)return;
            if(children==null){children=new Octree[8];int old=index;index=-1;child(p[old]).insert(old,p);}
            child(v).insert(i,p);
        }
        Octree child(double[] v){int k=(v[0]>=x?1:0)|(v[1]>=y?2:0)|(v[2]>=z?4:0);if(children[k]==null)children[k]=new Octree(x+((k&1)>0?1:-1)*half/2,y+((k&2)>0?1:-1)*half/2,z+((k&4)>0?1:-1)*half/2,half/2,depth+1);return children[k];}
        void force(int i,double[][] p,double[] f,double k2){
            if(mass==0 || (mass==1 && index==i))return;
            double dx=p[i][0]-mx,dy=p[i][1]-my,dz=p[i][2]-mz,d2=dx*dx+dy*dy+dz*dz+.01;
            boolean contains=Math.abs(p[i][0]-x)<=half && Math.abs(p[i][1]-y)<=half && Math.abs(p[i][2]-z)<=half;
            if(children==null || (!contains && half*2/Math.sqrt(d2)<.7)){double scale=k2*mass/d2;f[0]+=dx*scale;f[1]+=dy*scale;f[2]+=dz*scale;}
            else for(Octree c:children)if(c!=null)c.force(i,p,f,k2);
        }
    }
    public static double[][] run(Scene s,int iterations,long milliseconds,BooleanSupplier cancelled,BiConsumer<Integer,double[][]> progress){
        int n=s.nodes.size();double[][] p=new double[n][3];
        for(int i=0;i<n;i++){Scene.Vertex v=s.nodes.get(i);p[i]=new double[]{v.x,v.y,v.z};}
        if(n<2)return p;
        double k=Math.cbrt(800.0*800*800/n),temp=30;long deadline=System.nanoTime()+milliseconds*1_000_000;
        for(int step=0;step<iterations && System.nanoTime()<deadline && !cancelled.getAsBoolean();step++){
            double bound=1;for(double[] v:p)for(double c:v)bound=Math.max(bound,Math.abs(c)+1);
            Octree tree=new Octree(0,0,0,bound,0);for(int i=0;i<n;i++)tree.insert(i,p);
            double[][] f=new double[n][3];for(int i=0;i<n;i++)tree.force(i,p,f[i],k*k);
            for(Scene.Link e:s.edges){double[] a=p[e.source],b=p[e.target];double dx=a[0]-b[0],dy=a[1]-b[1],dz=a[2]-b[2];double d=Math.sqrt(dx*dx+dy*dy+dz*dz)+.01;double scale=d/k*Math.sqrt(Math.max(.01,Math.min(10,Math.abs(e.weight))));double[] fs=f[e.source],ft=f[e.target];fs[0]-=dx*scale;fs[1]-=dy*scale;fs[2]-=dz*scale;ft[0]+=dx*scale;ft[1]+=dy*scale;ft[2]+=dz*scale;}
            for(int i=0;i<n;i++)if(!s.nodes.get(i).pinned){double[] f0=f[i];for(int d=0;d<3;d++)f0[d]-=p[i][d]*.015;double len=Math.sqrt(Scene.sq(f0[0])+Scene.sq(f0[1])+Scene.sq(f0[2]))+.001;double scale=Math.min(temp,len)/len;for(int d=0;d<3;d++)if(d!=2 || s.layerAttribute.isEmpty())p[i][d]+=f0[d]*scale;}
            temp=Math.max(.2,temp*.994);if(step%10==0)progress.accept(step,copy(p));
        }
        return p;
    }
    static double[][] copy(double[][] p){double[][] c=new double[p.length][];for(int i=0;i<p.length;i++)c[i]=p[i].clone();return c;}
}
