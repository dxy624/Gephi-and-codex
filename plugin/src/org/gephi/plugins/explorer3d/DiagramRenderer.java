package org.gephi.plugins.explorer3d;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.awt.TextRenderer;
import org.joml.Matrix4f;

import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Styles the current camera projection without moving nodes or changing graph attributes. */
final class DiagramRenderer {
    private static final int[] PALETTE = {
        0x256eae, 0x3b8862, 0x955cba, 0xd38430, 0x1597a4,
        0xc45c6c, 0x716634, 0x52668c, 0xaf5b3c, 0x618025
    };
    static final int ACCENT = 0xf4ad39;
    private static final int BATCH_VERTICES = 8190;
    private final FloatBuffer edgePositions = Buffers.newDirectFloatBuffer(3 * BATCH_VERTICES);
    private final FloatBuffer edgeColors = Buffers.newDirectFloatBuffer(4 * BATCH_VERTICES);
    private FloatBuffer nodePositions = Buffers.newDirectFloatBuffer(3);
    private FloatBuffer nodeColors = Buffers.newDirectFloatBuffer(4);
    private FloatBuffer nodeSizes = Buffers.newDirectFloatBuffer(1);
    private TextRenderer text;
    private int textSize;

    record Node(Scene.Vertex vertex, float x, float y, float z, float radius,
                int rgb, float alpha, double importance, boolean selected, boolean emphasized,
                boolean leaderCommunity) { }

    record Projection(Node[] nodes, Matrix4f matrix, Map<String, Integer> palette, Set<String> leaders,
                      float unit, double maxWeight, boolean classic, boolean weighted,
                      boolean curved, boolean keywords) { }

    record EdgeStyle(int color, float alpha, float width, boolean curved, float arrowLength) { }
    record Arrow(float tipX, float tipY, float leftX, float leftY, float rightX, float rightY) { }
    record EdgeShape(float ax, float ay, float cx, float cy, float bx, float by,
                     boolean curved, float finish, Arrow arrow) {
        float x(float t) { return quadratic(ax, cx, bx, t); }
        float y(float t) { return quadratic(ay, cy, by, t); }
        private static float quadratic(float start, float control, float end, float t) {
            float rest = 1 - t;
            return rest * rest * start + 2 * rest * t * control + t * t * end;
        }
    }

    static Projection project(Scene scene, int width, int height, boolean paper) {
        float unit = Math.max(.55f, height / 850f);
        boolean geographic = scene.geographyEnabled;
        boolean classic = paper && GraphRenderer.usesClassicRenderer(scene);
        boolean weighted = !geographic && scene.visualStyle.equals("weighted");
        boolean curved = geographic || scene.visualStyle.equals("communities");
        boolean keywords = scene.visualStyle.equals("keywords");
        Set<String> leaders = scene.geography != null && (geographic || paper)
            ? Set.copyOf(scene.geography.leaders) : Set.of();
        Set<String> leaderGroups = geographic ? Set.copyOf(scene.geography.communities) : Set.of();
        Node[] projection = new Node[scene.nodes.size()];
        double[] ranks = new double[projection.length];
        double maxWeight = 0;
        for (Scene.Link edge : scene.edges) maxWeight = Math.max(maxWeight, edge.weight);
        if (weighted && maxWeight > 0) {
            for (Scene.Link edge : scene.edges) {
                double weight = Math.max(0, edge.weight) / maxWeight;
                ranks[edge.source] += weight;
                if (edge.target != edge.source) ranks[edge.target] += weight;
            }
        }
        Map<String, Integer> palette = palette(scene);
        NodeColors nodeColors = scene.colors();
        double maxRank = 0;
        for (int i = 0; i < ranks.length; i++) {
            if (!weighted || maxWeight == 0) ranks[i] = scene.nodes.get(i).degree;
            maxRank = Math.max(maxRank, ranks[i]);
        }
        Matrix4f matrix = GraphRenderer.matrix(scene, width, height);
        Matrix4f classicView = classic && scene.perspective ? GraphRenderer.view(scene) : null;
        for (int i = 0; i < projection.length; i++) {
            Scene.Vertex vertex = scene.nodes.get(i);
            if (!scene.visible(vertex)) continue;
            float[] p = GraphRenderer.project(scene, matrix, vertex, width, height);
            if (p == null || p[2] < -1 || p[2] > 1) continue;
            double importance = maxRank > 0 ? Math.sqrt(ranks[i] / maxRank) : 0;
            boolean isLeader = leaders.contains(vertex.id);
            boolean inLeaderCommunity = leaderGroups.contains(vertex.community);
            boolean selected = isLeader || scene.selection.contains(vertex.id);
            boolean emphasized = selected || scene.highlight.contains(vertex.id);
            float depthScale = scene.perspective ? (float) Math.max(.68, Math.min(1.35,
                scene.distance * (1 - p[2]) / 2)) : 1;
            double baseRadius = geographic ? 3 + importance * 8
                : scene.visualStyle.equals("classic") ? vertex.size / 2
                : 2.5 + importance * (keywords ? 23 : weighted ? 22 : 17);
            if (geographic) baseRadius *= isLeader ? 1.7 : inLeaderCommunity ? 1.4 : 1;
            float radius = (float) Math.max(1.5, Math.min(60, baseRadius * unit * scene.nodeScale
                * depthScale * (selected ? 1.14 : 1)));
            int color = weighted ? blend(0xaed9cd, 0x14604d, importance)
                : palette.getOrDefault(vertex.community, originalColor(vertex));
            if (classic) {
                double perspectiveScale = 1;
                if (classicView != null) {
                    double viewZ = classicView.m02() * vertex.x + classicView.m12() * vertex.y
                        + classicView.m22() * vertex.z + classicView.m32();
                    perspectiveScale = 1000 / Math.max(20, -viewZ);
                }
                radius = (float) Math.max(1.5, Math.min(60, vertex.size * scene.nodeScale * height / 1800
                    * perspectiveScale * (selected ? 1.5 : 1)));
                color = originalColor(vertex);
            }
            if (nodeColors.active()) color = nodeColors.color(vertex);
            if (selected) color = ACCENT;
            float alpha = scene.highlight.isEmpty() || emphasized ? 1 : .24f;
            if (geographic && !leaderGroups.isEmpty() && !inLeaderCommunity && !selected) alpha *= .56f;
            projection[i] = new Node(vertex, p[0], p[1], p[2], radius, color, alpha, importance,
                selected, emphasized, inLeaderCommunity);
        }
        return new Projection(projection, matrix, palette, leaders, unit, maxWeight, classic, weighted, curved, keywords);
    }

    static EdgeStyle edgeStyle(Scene scene, Projection projection, Scene.Link edge, Node from, Node to) {
        boolean hot = scene.pathEdges.isEmpty()
            ? scene.highlight.contains(from.vertex.id) && scene.highlight.contains(to.vertex.id)
            : scene.pathEdges.contains(edge.id);
        if (projection.classic) {
            int color = hot ? ACCENT : scene.dark ? 0x80a6cc : 0x596673;
            float alpha = hot ? .85f : scene.highlight.isEmpty() ? .25f : .035f;
            return new EdgeStyle(color, alpha, (float) scene.edgeWidth, false, 0);
        }
        float unit = projection.unit;
        boolean weighted = projection.weighted;
        boolean curved = projection.curved;
        double maxWeight = projection.maxWeight;
        double relative = maxWeight > 0 ? Math.sqrt(Math.max(0, edge.weight) / maxWeight) : 0;
        float width = (float) Math.max(.65, Math.min(15, unit * scene.edgeWidth *
            (weighted ? .7 + 5.5 * relative : .55 + 1.9 * relative)));
        float alpha = hot ? .88f : !scene.highlight.isEmpty() ? .09f
            : (float) (weighted ? .22 + .62 * relative : curved ? .23 : .31 + .27 * relative);
        int color = hot ? ACCENT : weighted ? blend(0x8ebdb3, 0x155a49, relative)
            : blend(from.rgb, to.rgb, .5);
        if (scene.geographyEnabled) {
            if (from.leaderCommunity && to.leaderCommunity && from.vertex.community.equals(to.vertex.community)) {
                width *= 1.85f;
                alpha = (float) (.64 + .25 * relative);
                if (!scene.colors().active()) color = projection.palette.getOrDefault(from.vertex.community, color);
            } else if (!scene.geography.communities.isEmpty() && !hot) alpha = (float) (.20 + .19 * relative);
        }
        float arrowLength = scene.showsArrows() && edge.directed
            ? (float) (unit * scene.arrowScale * (7 + 16 * relative)) : 0;
        return new EdgeStyle(color, alpha, width, curved || (weighted && edge.directed), arrowLength);
    }

    void draw(GL2 gl, Scene scene, int width, int height, GraphRenderer renderer) {
        if (width < 1 || height < 1) return;
        Projection projected = project(scene, width, height, false);
        Node[] projection = projected.nodes;
        float unit = projected.unit;
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();
        gl.glOrtho(0, width, height, 0, -1, 1);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glLoadIdentity();
        gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL2.GL_COLOR_ARRAY);
        gl.glDepthMask(false);
        for (Scene.Link edge : scene.edges) {
            Node from = projection[edge.source];
            Node to = projection[edge.target];
            if (from == null || to == null || edge.weight < scene.minWeight) continue;
            EdgeStyle style = edgeStyle(scene, projected, edge, from, to);
            EdgeShape shape = edgeShape(from, to, unit, style);
            if (shape != null) drawEdge(gl, from, to, style, shape);
        }
        flush(gl);
        gl.glDepthMask(true);
        drawNodes(gl, renderer, projection);
        gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glDisableClientState(GL2.GL_COLOR_ARRAY);
        gl.glDisable(GL.GL_DEPTH_TEST);
        if (text == null) {
            textSize = Math.max(48, Math.min(192, Math.round(48 * unit)));
            Font font = new Font("Microsoft YaHei", Font.PLAIN, textSize);
            if (font.canDisplayUpTo("示例网络 · Gephi") >= 0) font = new Font("SansSerif", Font.PLAIN, textSize);
            text = new TextRenderer(font, true, true);
        }
        drawLabels(text, scene, projection, projected.matrix, width, height, unit, projected.keywords);
    }

    void dispose() {
        if (text != null) {
            text.dispose();
            text = null;
        }
    }

    private static Map<String, Integer> palette(Scene scene) {
        var groups = new TreeSet<String>();
        for (Scene.Vertex node : scene.nodes) if (!node.community.isBlank()) groups.add(node.community);
        Map<String, Integer> result = new HashMap<>();
        int color = 0;
        for (String group : groups) result.put(group, paletteColor(color++));
        return result;
    }

    static int paletteColor(int index) { return PALETTE[index % PALETTE.length]; }

    static EdgeShape edgeShape(Node from, Node to, float unit, EdgeStyle style) {
        boolean curved = style.curved;
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        float distance = (float) Math.hypot(dx, dy);
        float ax, ay, bx, by, cx, cy;
        if (from == to) {
            ax = from.x + .72f * from.radius;
            ay = from.y - .72f * from.radius;
            bx = from.x + .94f * from.radius;
            by = from.y + .34f * from.radius;
            cx = from.x + from.radius * 2 + 18 * unit;
            cy = from.y - from.radius * 1.8f - 13 * unit;
            curved = true;
        } else {
            if (distance < from.radius + to.radius + 2) return null;
            float bend = curved ? Math.min(distance * .20f, 65 * unit) : 0;
            cx = (from.x + to.x) / 2 - bend * dy / distance;
            cy = (from.y + to.y) / 2 + bend * dx / distance;
            float first = (float) Math.hypot(cx - from.x, cy - from.y);
            float last = (float) Math.hypot(cx - to.x, cy - to.y);
            ax = from.x + (cx - from.x) * from.radius / first;
            ay = from.y + (cy - from.y) * from.radius / first;
            bx = to.x + (cx - to.x) * to.radius / last;
            by = to.y + (cy - to.y) * to.radius / last;
        }
        float finish = 1;
        Arrow arrow = null;
        if (style.arrowLength > 0) {
            float dxEnd = bx - cx, dyEnd = by - cy;
            float tangent = (float) Math.hypot(dxEnd, dyEnd);
            float reach = from == to ? tangent : (float) Math.hypot(bx - ax, by - ay);
            float head = Math.min(style.arrowLength, Math.min(reach * .60f, tangent));
            if (tangent > .1f && head > .1f) {
                finish = 1 - head / (2 * tangent);
                float rest = 1 - finish;
                float baseX = rest * rest * ax + 2 * rest * finish * cx + finish * finish * bx;
                float baseY = rest * rest * ay + 2 * rest * finish * cy + finish * finish * by;
                float offX = -dyEnd * head * .54f / tangent;
                float offY = dxEnd * head * .54f / tangent;
                arrow = new Arrow(bx, by, baseX + offX, baseY + offY, baseX - offX, baseY - offY);
            }
        }
        return new EdgeShape(ax, ay, cx, cy, bx, by, curved, finish, arrow);
    }

    private void drawEdge(GL2 gl, Node from, Node to, EdgeStyle style, EdgeShape shape) {
        Arrow arrow = shape.arrow;
        if (arrow != null) {
            triangle(gl, arrow.tipX, arrow.tipY, to.z, arrow.leftX, arrow.leftY, to.z, arrow.rightX,
                arrow.rightY, to.z, style.color, Math.min(1, style.alpha * 1.4f));
        }
        float previousX = shape.ax, previousY = shape.ay, previousZ = from.z;
        int steps = shape.curved ? 9 : 1;
        for (int i = 1; i <= steps; i++) {
            float t = shape.finish * i / steps;
            float rest = 1 - t;
            float x = shape.x(t);
            float y = shape.y(t);
            float z = rest * from.z + t * to.z;
            segment(gl, previousX, previousY, previousZ, x, y, z, style.width, style.color, style.alpha);
            previousX = x;
            previousY = y;
            previousZ = z;
        }
    }

    private void segment(GL2 gl, float ax, float ay, float az, float bx, float by, float bz,
                         float width, int color, float alpha) {
        float length = (float) Math.hypot(bx - ax, by - ay);
        if (length < .1f) return;
        float nx = (ay - by) * width / (2 * length);
        float ny = (bx - ax) * width / (2 * length);
        triangle(gl, ax + nx, ay + ny, az, ax - nx, ay - ny, az, bx - nx, by - ny, bz, color, alpha);
        triangle(gl, ax + nx, ay + ny, az, bx - nx, by - ny, bz, bx + nx, by + ny, bz, color, alpha);
    }

    private void triangle(GL2 gl, float ax, float ay, float az, float bx, float by, float bz,
                          float cx, float cy, float cz, int rgb, float alpha) {
        if (edgePositions.remaining() < 9) flush(gl);
        edgePositions.put(ax).put(ay).put(-az).put(bx).put(by).put(-bz).put(cx).put(cy).put(-cz);
        for (int i = 0; i < 3; i++) putColor(edgeColors, rgb, alpha);
    }

    private void flush(GL2 gl) {
        int count = edgePositions.position() / 3;
        if (count == 0) return;
        edgePositions.flip();
        edgeColors.flip();
        gl.glVertexPointer(3, GL.GL_FLOAT, 0, edgePositions);
        gl.glColorPointer(4, GL.GL_FLOAT, 0, edgeColors);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, count);
        edgePositions.clear();
        edgeColors.clear();
    }

    private void drawNodes(GL2 gl, GraphRenderer renderer, Node[] projection) {
        if (nodeSizes.capacity() < projection.length) {
            nodePositions = Buffers.newDirectFloatBuffer(projection.length * 3);
            nodeColors = Buffers.newDirectFloatBuffer(projection.length * 4);
            nodeSizes = Buffers.newDirectFloatBuffer(projection.length);
        }
        nodePositions.clear();
        nodeColors.clear();
        nodeSizes.clear();
        for (Node node : projection) {
            if (node == null) continue;
            nodePositions.put(node.x).put(node.y).put(-node.z);
            putColor(nodeColors, node.rgb, node.alpha);
            nodeSizes.put(node.radius * 2);
        }
        int count = nodeSizes.position();
        nodePositions.flip();
        nodeColors.flip();
        nodeSizes.flip();
        if (count == 0) return;
        gl.glVertexPointer(3, GL.GL_FLOAT, 0, nodePositions);
        gl.glColorPointer(4, GL.GL_FLOAT, 0, nodeColors);
        renderer.drawPoints(gl, nodeSizes, count, 1, false, true);
    }

    private void drawLabels(TextRenderer text, Scene scene, Node[] projection, Matrix4f matrix, int width,
                            int height, float unit, boolean keywords) {
        text.beginRendering(width, height);
        float ink = scene.dark && !scene.geographyEnabled ? .95f : .12f;
        float fontScale = 14f * unit / textSize;
        List<Rectangle2D> occupied = new ArrayList<>();
        if (scene.labels) {
            List<Node> order = new ArrayList<>();
            for (Node node : projection) if (node != null && node.x >= 0 && node.x <= width
                && node.y >= 0 && node.y <= height && !node.vertex.label.isBlank()) order.add(node);
            order.sort(Comparator.comparing(Node::selected).thenComparing(Node::emphasized)
                .thenComparingDouble(Node::importance).reversed().thenComparing(Node::vertex,
                    Comparator.comparing(vertex -> vertex.id)));
            for (int rank = 0; rank < Math.min(180, order.size()); rank++) {
                if (occupied.size() >= (keywords ? 65 : 42)) break;
                Node node = order.get(rank);
                if (scene.geographyEnabled && !node.selected) continue;
                if (!scene.highlight.isEmpty() && !node.emphasized) continue;
                String label = node.vertex.label.replace('\r', ' ').replace('\n', ' ');
                Rectangle2D bounds = text.getBounds(label);
                float scale = fontScale * (float) (.85 + node.importance * (keywords ? 1.47 : .82));
                float tw = (float) bounds.getWidth() * scale;
                float th = (float) bounds.getHeight() * scale;
                float gap = node.radius + 4 * unit;
                float[] xs = {node.x + gap, node.x - gap - tw, node.x - tw / 2, node.x - tw / 2,
                    node.x + gap * .75f, node.x - gap * .75f - tw, node.x + gap * .75f, node.x - gap * .75f - tw};
                float[] ys = {node.y - th / 2, node.y - th / 2, node.y - gap - th, node.y + gap,
                    node.y - gap * .75f - th, node.y - gap * .75f - th, node.y + gap * .75f, node.y + gap * .75f};
                int placements = scene.geographyEnabled ? xs.length : 4;
                for (int i = 0; i < placements; i++) {
                    Rectangle2D box = new Rectangle2D.Float(xs[i] - 3, ys[i] - 2, tw + 6, th + 4);
                    if (box.getMinX() < 6 || box.getMaxX() > width - 6 || box.getMinY() < 6
                        || box.getMaxY() > height - 29 * unit || occupied.stream().anyMatch(box::intersects)
                        || overlapsNode(box, order, node)) continue;
                    float tx = xs[i] - (float) bounds.getX() * scale;
                    float ty = height - ys[i] - th - (float) bounds.getY() * scale;
                    float backdrop = scene.dark && !scene.geographyEnabled ? .08f : 1;
                    text.setColor(backdrop, backdrop, backdrop, .82f);
                    for (int offset = -1; offset <= 1; offset += 2) {
                        text.draw3D(label, tx + offset * unit, ty, 0, scale);
                        text.draw3D(label, tx, ty + offset * unit, 0, scale);
                    }
                    text.setColor(ink, ink, ink, 1);
                    text.draw3D(label, tx, ty, 0, scale);
                    occupied.add(box);
                    break;
                }
            }
        }
        if (scene.geographyEnabled) {
            text.setColor(.20f, .31f, .39f, .94f);
            for (GeoLayer.City city : scene.geography.cities) {
                float[] p = GraphRenderer.project(matrix, city.x(), city.y(), 0, width, height);
                if (p == null || p[2] < -1 || p[2] > 1) continue;
                String label = city.name() + (city.count() > 1 ? " · " + city.count() : "");
                var bounds = text.getBounds(label);
                float scale = fontScale * .9f;
                float x = p[0] + 10 * unit, y = p[1] + 4 * unit;
                var box = new Rectangle2D.Float(x - 3, y, (float) bounds.getWidth() * scale + 6,
                    (float) bounds.getHeight() * scale + 4);
                if (x < 0 || box.getMaxX() > width || y < 0 || box.getMaxY() > height - 30 * unit
                    || occupied.stream().anyMatch(box::intersects)) continue;
                text.draw3D(label, x - (float) bounds.getX() * scale,
                    height - y - (float) (bounds.getHeight() + bounds.getY()) * scale, 0, scale);
                occupied.add(box);
            }
        }
        text.setColor(ink, ink, ink, .68f);
        String caption = (scene.synthetic ? "合成示例 · " : "") + scene.nodes.size() + " 节点 · " + scene.edges.size() + " 边";
        if (scene.geographyEnabled) caption += " · 已定位 " + scene.geography.positions.size() + " 家 · 城市中心 · " + scene.geography.source;
        text.draw3D(caption, 16 * unit, 13 * unit, 0, .85f * fontScale);
        text.endRendering();
    }

    static boolean overlapsNode(Rectangle2D box, List<Node> visible, Node owner) {
        for (Node node : visible) {
            if (node == owner) continue;
            double x = Math.max(box.getMinX(), Math.min(box.getMaxX(), node.x));
            double y = Math.max(box.getMinY(), Math.min(box.getMaxY(), node.y));
            if (Scene.sq(x - node.x) + Scene.sq(y - node.y) < Scene.sq(node.radius)) return true;
        }
        return false;
    }

    private static int originalColor(Scene.Vertex node) {
        return (Math.round(node.r * 255) << 16) | (Math.round(node.g * 255) << 8) | Math.round(node.b * 255);
    }

    static int blend(int a, int b, double share) {
        int rgb = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int start = (a >> shift) & 255;
            int end = (b >> shift) & 255;
            rgb |= (int) Math.round(start + (end - start) * share) << shift;
        }
        return rgb;
    }

    private static void putColor(FloatBuffer buffer, int rgb, float alpha) {
        buffer.put(((rgb >> 16) & 255) / 255f).put(((rgb >> 8) & 255) / 255f).put((rgb & 255) / 255f).put(alpha);
    }
}
