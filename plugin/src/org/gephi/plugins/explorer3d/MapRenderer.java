package org.gephi.plugins.explorer3d;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.glu.GLUtessellatorCallbackAdapter;

/** Draws vector boundaries on the same ground plane as the city's network positions. */
final class MapRenderer {
    private GeoLayer cachedLayer;
    private int displayList;

    void draw(GL2 gl, GeoLayer layer, float[] matrix) {
        if (displayList == 0 || cachedLayer != layer) {
            dispose(gl);
            displayList = gl.glGenLists(1);
            gl.glNewList(displayList, GL2.GL_COMPILE);
            compile(gl, layer);
            gl.glEndList();
            cachedLayer = layer;
        }
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadMatrixf(matrix, 0);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glLoadIdentity();
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(false);
        gl.glCallList(displayList);
        gl.glDepthMask(true);
        gl.glEnable(GL.GL_DEPTH_TEST);
    }

    private static void compile(GL2 gl, GeoLayer layer) {
        var tess = GLU.gluNewTess();
        var callback = new GLUtessellatorCallbackAdapter() {
            @Override public void begin(int type) { gl.glBegin(type); }
            @Override public void end() { gl.glEnd(); }
            @Override public void vertex(Object vertex) { gl.glVertex3dv((double[]) vertex, 0); }
            @Override public void combine(double[] point, Object[] values, float[] weights, Object[] result) {
                result[0] = point.clone();
            }
        };
        GLU.gluTessCallback(tess, GLU.GLU_TESS_BEGIN, callback);
        GLU.gluTessCallback(tess, GLU.GLU_TESS_END, callback);
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX, callback);
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE, callback);
        GLU.gluTessProperty(tess, GLU.GLU_TESS_WINDING_RULE, GLU.GLU_TESS_WINDING_ODD);
        GLU.gluTessNormal(tess, 0, 0, 1);
        gl.glColor4f(.92f, .95f, .96f, 1);
        for (double[][][] polygon : layer.polygons) {
            GLU.gluTessBeginPolygon(tess, null);
            for (double[][] ring : polygon) {
                GLU.gluTessBeginContour(tess);
                for (int i = 0; i < ring.length - 1; i++) {
                    double[] point = {ring[i][0], ring[i][1], 0};
                    GLU.gluTessVertex(tess, point, 0, point);
                }
                GLU.gluTessEndContour(tess);
            }
            GLU.gluTessEndPolygon(tess);
        }
        GLU.gluDeleteTess(tess);
        gl.glLineWidth(1);
        gl.glColor4f(.60f, .69f, .73f, .85f);
        for (double[][][] polygon : layer.polygons) for (double[][] ring : polygon) outline(gl, ring);
        for (double[][] line : layer.lines) outline(gl, line);
    }

    private static void outline(GL2 gl, double[][] path) {
        gl.glBegin(GL.GL_LINE_STRIP);
        for (double[] p : path) gl.glVertex3d(p[0], p[1], 0);
        gl.glEnd();
    }

    void dispose(GL2 gl) {
        if (displayList != 0) gl.glDeleteLists(displayList, 1);
        displayList = 0;
        cachedLayer = null;
    }
}
