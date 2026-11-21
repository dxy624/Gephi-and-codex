package org.gephi.plugins.explorer3d;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Editable 2D artwork projected from a snapshot of the current 3D view. */
final class PaperSvg {
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
    private static final Font FONT = new Font("Microsoft YaHei", Font.PLAIN, 12);
    private static final int LABEL_LIMIT = 60;
    private static final int MAX_CONTEXT_LABELS = 12;
    private static final int FOCUS_INK = 0xa34121;
    private static final int CONTEXT_INK = 0x687c8a;

    private record Edge(Scene.Link link, DiagramRenderer.EdgeShape shape, DiagramRenderer.EdgeStyle style,
                        boolean focus) { }
    private record Label(String id, String text, float x, float y, boolean priority, Rectangle2D box,
                         float anchorX, float anchorY, boolean connector, float size) { }
    record Key(String id, String text, int color, float size, boolean line, boolean arrow) { }
    private record PlacedKey(Key key, float x, int row) { }

    private final Scene scene;
    private final DiagramRenderer.Projection projection;
    private final List<DiagramRenderer.Node> visible;
    private final List<DiagramRenderer.Node> focusObstacles;
    private final List<Edge> edges = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Set<String> priorityIds;
    private final Set<String> relatedIds = new LinkedHashSet<>();
    private final boolean focusActive;
    private final int width, height;
    private final float fontSize, padding;
    private final Font font;
    private final boolean dark;
    private final String title, direction;
    private int unplacedPriority, drawnArrows, focusEdges, drawnFocusEdges, unlocatedFocusEdges;

    static Map<String, Object> export(Scene scene, String file, int width, int height, double widthMm,
                                      String title, String direction) {
        Path output = Path.of(file);
        if (!output.isAbsolute() || !file.toLowerCase(Locale.ROOT).endsWith(".svg")) {
            throw new IllegalArgumentException("An absolute .svg path is required");
        }
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists; choose a new file name");
        if (width < 300 || width > 8000 || height < 200 || height > 8000 ||
            !Double.isFinite(widthMm) || widthMm < 60 || widthMm > 300) {
            throw new IllegalArgumentException("View size must be 300–8000 by 200–8000, with a paper width of 60–300 mm");
        }
        PaperSvg paper = new PaperSvg(scene, width, height, widthMm, title, direction, true);
        try {
            Path temporary = Files.createTempFile(output.getParent(), ".gephi-paper-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    paper.write(XMLOutputFactory.newFactory().createXMLStreamWriter(writer), widthMm);
                }
                Files.move(temporary, output);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | XMLStreamException ex) {
            throw new IllegalStateException("SVG export failed: " + ex.getMessage(), ex);
        }
        return Map.of("success", true, "path", file, "workspace_id", scene.workspaceId, "revision", scene.revision,
            "shown_nodes", paper.visible.size(), "shown_edges", paper.edges.size(), "shown_arrows", paper.drawnArrows,
            "labels", paper.labels.size(), "unplaced_priority_labels", paper.unplacedPriority,
            "priority_labels", paper.priorityIds.size());
    }

    private PaperSvg(Scene scene, int width, int height, double widthMm, String title, String direction, boolean includeLabels) {
        this.scene = scene;
        this.width = width;
        this.height = height;
        fontSize = (float) (width * 6.8 * 25.4 / (widthMm * 72));
        font = FONT.deriveFont(fontSize);
        padding = fontSize * 1.2f;
        dark = scene.dark && !scene.geographyEnabled;
        this.direction = direction.isBlank() ? defaultDirection(scene) : direction;
        this.title = title.isBlank()
            ? (scene.geographyEnabled ? "企业地理关系图" : "网络关系图") : title;
        priorityIds = new LinkedHashSet<>(scene.selection);
        if (scene.geography != null) priorityIds.addAll(scene.geography.leaders);
        focusActive = !priorityIds.isEmpty() || !scene.highlight.isEmpty() || !scene.pathEdges.isEmpty();
        if (focusActive) frameFocus();
        projection = DiagramRenderer.project(scene, width, height, true);
        visible = Arrays.stream(projection.nodes()).filter(node -> node != null &&
            node.x() + node.radius() >= 0 && node.x() - node.radius() <= width &&
            node.y() + node.radius() >= 0 && node.y() - node.radius() <= height)
            .sorted(Comparator.comparingDouble(DiagramRenderer.Node::z).reversed()).toList();
        prepareEdges();
        focusObstacles = visible.stream().filter(node -> priorityIds.contains(node.vertex().id)
            || relatedIds.contains(node.vertex().id)).toList();
        if (includeLabels) prepareLabels();
        prepareLegend();
    }

    private boolean isFocusEdge(Scene.Link link) {
        if (!scene.pathEdges.isEmpty()) return scene.pathEdges.contains(link.id);
        String from = scene.nodes.get(link.source).id;
        String to = scene.nodes.get(link.target).id;
        if (priorityIds.contains(from) || priorityIds.contains(to)) return true;
        if (scene.highlight.contains(from) && scene.highlight.contains(to)) return true;
        if (!scene.geographyEnabled) return false;
        String community = scene.nodes.get(link.source).community;
        return community.equals(scene.nodes.get(link.target).community) && scene.geography.communities.contains(community);
    }

    private void frameFocus() {
        Set<String> endpoints = new LinkedHashSet<>(priorityIds);
        endpoints.addAll(scene.highlight);
        for (Scene.Link link : scene.edges) {
            Scene.Vertex from = scene.nodes.get(link.source);
            Scene.Vertex to = scene.nodes.get(link.target);
            if (link.weight >= scene.minWeight && isFocusEdge(link) && scene.visible(from) && scene.visible(to)) {
                endpoints.add(from.id);
                endpoints.add(to.id);
            }
        }
        var matrix = GraphRenderer.matrix(scene, width, height);
        var positions = scene.nodes.stream().filter(node -> endpoints.contains(node.id) && scene.visible(node))
            .map(node -> scene.geographyEnabled ? scene.geography.positions.get(node.id) : new double[]{node.x, node.y, node.z})
            .toList();
        float margin = fontSize * 2;
        boolean outside = positions.stream().anyMatch(p -> {
            float[] point = GraphRenderer.project(matrix, p[0], p[1], p[2], width, height);
            return point == null || point[0] < margin || point[0] > width - margin
                || point[1] < margin || point[1] > height - margin || Math.abs(point[2]) > 1;
        });
        if (!outside) return;
        var rotation = new Matrix4f().rotateX((float) scene.pitch).rotateY((float) scene.yaw);
        var min = new Vector3f(Float.POSITIVE_INFINITY);
        var max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (double[] p : positions) {
            var point = rotation.transformDirection(new Vector3f((float) p[0], (float) p[1], (float) p[2]));
            min.min(point);
            max.max(point);
        }
        var center = rotation.invert().transformDirection(new Vector3f(min).add(max).mul(.5f));
        float span = Math.max(max.y - min.y, (max.x - min.x) * height / width);
        scene.targetX = center.x;
        scene.targetY = center.y;
        scene.targetZ = center.z;
        scene.distance = Math.max(80, span) * 1.65 + (max.z - min.z) / 2;
    }

    static String defaultDirection(Scene scene) {
        if (scene.geography != null && scene.geography.direction != null && !scene.geography.direction.isBlank()) {
            return scene.geography.direction;
        }
        return scene.source.toLowerCase(Locale.ROOT).contains("suppliers_to_customers")
            ? "供应商 → 客户" : "起点 → 终点";
    }

    static List<Key> legendItems(Scene snapshot, int width, int height, double widthMm, String direction) {
        return List.copyOf(new PaperSvg(snapshot, width, height, widthMm, "", direction, false).keys);
    }

    private void prepareEdges() {
        float contextAlpha = Math.max(.05f, Math.min(.17f, (float) (3.5 / Math.sqrt(scene.edges.size() + 1))));
        for (Scene.Link link : scene.edges) {
            if (link.weight < scene.minWeight) continue;
            Scene.Vertex source = scene.nodes.get(link.source), target = scene.nodes.get(link.target);
            if (!scene.passesFilters(source) || !scene.passesFilters(target)) continue;
            boolean focus = focusActive && isFocusEdge(link);
            if (focus) {
                focusEdges++;
                if (!scene.located(source) || !scene.located(target)) unlocatedFocusEdges++;
            }
            var from = projection.nodes()[link.source];
            var to = projection.nodes()[link.target];
            if (from == null || to == null) continue;
            var style = DiagramRenderer.edgeStyle(scene, projection, link, from, to);
            if (focusActive) {
                float unit = projection.unit();
                style = focus
                    ? new DiagramRenderer.EdgeStyle(FOCUS_INK, .89f, Math.max(1.4f * unit, style.width() * 1.22f),
                        style.curved(), style.arrowLength() * 1.14f)
                    : new DiagramRenderer.EdgeStyle(CONTEXT_INK, Math.min(contextAlpha, style.alpha()),
                        style.width() * .64f, style.curved(),
                        style.arrowLength() * .66f);
            }
            var shape = DiagramRenderer.edgeShape(from, to, projection.unit(), style);
            if (shape == null) continue;
            float pad = style.width() + style.arrowLength();
            if (Math.max(shape.ax(), Math.max(shape.bx(), shape.cx())) < -pad ||
                Math.min(shape.ax(), Math.min(shape.bx(), shape.cx())) > width + pad ||
                Math.max(shape.ay(), Math.max(shape.by(), shape.cy())) < -pad ||
                Math.min(shape.ay(), Math.min(shape.by(), shape.cy())) > height + pad) continue;
            edges.add(new Edge(link, shape, style, focus));
            if (focus) {
                drawnFocusEdges++;
                relatedIds.add(from.vertex().id);
                relatedIds.add(to.vertex().id);
            }
            if (shape.arrow() != null) drawnArrows++;
        }
        edges.sort(Comparator.comparing(Edge::focus));
    }

    private void prepareLabels() {
        var order = visible.stream()
            .sorted(Comparator.<DiagramRenderer.Node, Boolean>comparing(n -> projection.leaders().contains(n.vertex().id))
                .thenComparing(DiagramRenderer.Node::selected)
                .thenComparing(n -> relatedIds.contains(n.vertex().id))
                .thenComparing(DiagramRenderer.Node::emphasized)
                .thenComparingDouble(DiagramRenderer.Node::importance).reversed()
                .thenComparing(n -> n.vertex().id))
            .toList();
        int context = 0;
        int placedPriority = 0;
        for (var node : order) {
            if (labels.size() >= LABEL_LIMIT) break;
            boolean priority = priorityIds.contains(node.vertex().id);
            if (!priority && (!scene.labels || context >= MAX_CONTEXT_LABELS
                || (focusActive || scene.geographyEnabled) && !relatedIds.contains(node.vertex().id))) continue;
            String label = node.vertex().label.isBlank() ? node.vertex().id : node.vertex().label;
            String text = shorten(label, width * .38f);
            float radius = priority ? Math.max(node.radius(), 4.6f * projection.unit()) : node.radius();
            if (placeLabel(node.vertex().id, text, node.x(), node.y(), radius, node, priority)) {
                if (priority) placedPriority++;
                else context++;
            }
        }
        unplacedPriority = priorityIds.size() - placedPriority;
        if (scene.geographyEnabled && scene.labels) {
            int contextLimit = focusActive ? 7 : MAX_CONTEXT_LABELS;
            for (GeoLayer.City city : scene.geography.cities.stream()
                .sorted(Comparator.comparingInt(GeoLayer.City::count).reversed().thenComparing(GeoLayer.City::code)).toList()) {
                if (context >= contextLimit || labels.size() >= LABEL_LIMIT) break;
                float[] point = GraphRenderer.project(projection.matrix(), city.x(), city.y(), 0, width, height);
                if (point == null || point[2] < -1 || point[2] > 1 || point[0] < 0 || point[0] > width
                    || point[1] < 0 || point[1] > height) continue;
                if (placeLabel(city.code(), shorten(city.name(), width * .32f), point[0], point[1],
                    fontSize * .5f, null, false)) context++;
            }
        }
    }

    private boolean placeLabel(String id, String value, float x, float y, float radius,
                               DiagramRenderer.Node owner, boolean priority) {
        float size = fontSize * (priority ? 1.28f : .96f);
        Font labelFont = font.deriveFont(priority ? Font.BOLD : Font.PLAIN, size);
        float textWidth = (float) labelFont.getStringBounds(value, FONT_CONTEXT).getWidth();
        var metrics = labelFont.getLineMetrics(value, FONT_CONTEXT);
        float labelHeight = metrics.getAscent() + metrics.getDescent();
        boolean focusLabel = priority || owner != null && relatedIds.contains(owner.vertex().id);
        List<DiagramRenderer.Node> obstacles = focusLabel ? focusObstacles : visible;
        int rings = priority ? 5 : focusLabel ? 2 : 1;
        for (int ring = 0; ring < rings; ring++) {
            float gap = radius + fontSize * (.38f + ring * .8f);
            float[] xs = {x + gap, x - gap - textWidth, x - textWidth / 2, x - textWidth / 2,
                          x + gap, x - gap - textWidth, x + gap, x - gap - textWidth};
            float[] ys = {y - labelHeight / 2, y - labelHeight / 2, y - gap - labelHeight, y + gap,
                          y - gap - labelHeight, y - gap - labelHeight, y + gap, y + gap};
            for (int i = 0; i < xs.length; i++) {
                Rectangle2D box = new Rectangle2D.Float(xs[i] - 3, ys[i] - 2, textWidth + 6, labelHeight + 4);
                if (box.getMinX() >= 0 && box.getMinY() >= 0 && box.getMaxX() <= width && box.getMaxY() <= height
                    && labels.stream().noneMatch(label -> label.box.intersects(box))
                    && !DiagramRenderer.overlapsNode(box, obstacles, owner)) {
                    labels.add(new Label(id, value, xs[i], ys[i] + metrics.getAscent(), priority, box,
                        x, y, ring > 0, size));
                    return true;
                }
            }
        }
        return false;
    }

    private void prepareLegend() {
        boolean leaders = visible.stream().anyMatch(n -> projection.leaders().contains(n.vertex().id));
        boolean selected = visible.stream().anyMatch(n -> scene.selection.contains(n.vertex().id));
        if (leaders || selected) {
            String description = leaders && selected ? "链主 / 已选节点" : leaders ? "链主" : "已选节点";
            keys.add(new Key("selection", description, DiagramRenderer.ACCENT, 0, false, false));
        }
        if (drawnFocusEdges > 0) {
            keys.add(new Key("focus", "重点关系", FOCUS_INK, fontSize * .2f, true, false));
        }
        if (scene.colors().active()) {
            addFieldKeys(scene.colors());
        } else if (!focusActive && projection.weighted()) {
            keys.add(new Key("weighted_nodes", "节点色深 · " + (projection.maxWeight() > 0 ? "连接边权合计" : "度数"), 0x14604d, 0, false, false));
        } else if (!focusActive && !projection.classic()) {
            addCommunityKeys();
        }
        if (edges.stream().anyMatch(edge -> edge.link.directed)) {
            if (drawnArrows > 0) keys.add(new Key("direction", direction, CONTEXT_INK, fontSize * .1f, true, true));
            else notes.add("有向关系 " + direction + "（箭头已关闭）");
        }
        if (!projection.classic() && !edges.isEmpty()) {
            List<Edge> samples = drawnFocusEdges > 0 ? edges.stream().filter(Edge::focus).toList() : edges;
            Edge low = samples.stream().min(Comparator.comparingDouble(e -> e.link.weight)).orElseThrow();
            Edge high = samples.stream().max(Comparator.comparingDouble(e -> e.link.weight)).orElseThrow();
            if (low.link.weight != high.link.weight) {
                int ink = drawnFocusEdges > 0 ? FOCUS_INK : CONTEXT_INK;
                keys.add(new Key("weight_low", "边权 " + weight(low.link.weight), ink, low.style.width(), true, false));
                keys.add(new Key("weight_high", "边权 " + weight(high.link.weight), ink, high.style.width(), true, false));
            }
        }
        if (focusEdges > 0) notes.add("重点关系 " + drawnFocusEdges + " / " + focusEdges + " 条入图"
            + (unlocatedFocusEdges > 0 ? " · " + unlocatedFocusEdges + " 条缺少城市定位" : ""));
        String coverage = "视野内 " + visible.size() + " / " + scene.nodes.size() + " 个节点，"
            + edges.size() + " / " + scene.edges.size() + " 条边";
        notes.add(scene.geographyEnabled ? coverage + " · 底图 " + scene.geography.source : coverage);
        if (unplacedPriority > 0) notes.add(unplacedPriority + " 个重点标签在视野之外或未找到空位");
        if (scene.synthetic) notes.add("合成示例，非真实观测");
    }

    private void addFieldKeys(NodeColors colors) {
        String field = colors.field();
        List<Scene.Vertex> colored = visible.stream().filter(node -> !node.selected()).map(DiagramRenderer.Node::vertex).toList();
        if (colored.isEmpty()) return;
        String prefix = "field:" + field + ":";
        if (colors.numeric()) {
            if (colored.stream().anyMatch(node -> !colors.missing(node))) {
                if (colors.min() == colors.max()) {
                    keys.add(new Key(prefix + "same", field + " " + weight(colors.min()),
                        DiagramRenderer.blend(NodeColors.LOW, NodeColors.HIGH, .5), 0, false, false));
                } else {
                    keys.add(new Key(prefix + "min", field + " " + weight(colors.min()), NodeColors.LOW, 0, false, false));
                    keys.add(new Key(prefix + "max", field + " " + weight(colors.max()), NodeColors.HIGH, 0, false, false));
                }
            }
        } else {
            var groups = new java.util.TreeSet<String>();
            for (Scene.Vertex node : colored) if (!colors.missing(node)) groups.add(Scene.fieldValue(node, field));
            if (groups.size() > 6) {
                keys.add(new Key(prefix + "summary", field + " · " + groups.size() + " 类", CONTEXT_INK, 0, false, false));
            } else for (String group : groups) {
                keys.add(new Key(prefix + "category:" + group, field + " · " + group, colors.categories().get(group), 0, false, false));
            }
        }
        if (colored.stream().anyMatch(colors::missing)) {
            keys.add(new Key(prefix + "missing", field + " · 无可用值", NodeColors.MISSING, 0, false, false));
        }
    }

    private void addCommunityKeys() {
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (var node : visible) {
            String group = node.vertex().community;
            if (!group.isBlank()) groups.put(group, projection.palette().get(group));
        }
        if (groups.isEmpty()) return;
        boolean sharedColors = Set.copyOf(groups.values()).size() < groups.size();
        if (groups.size() > 6 || sharedColors) {
            keys.add(new Key("communities", groups.size() + " 个社区" + (sharedColors ? " · 部分共用颜色" : ""), CONTEXT_INK, 0, false, false));
        } else {
            groups.keySet().stream().sorted().forEach(group ->
                keys.add(new Key("community:" + group, "社区 " + group, groups.get(group), 0, false, false)));
        }
    }

    private List<PlacedKey> layoutKeys(float sample) {
        List<PlacedKey> placed = new ArrayList<>();
        float x = 0;
        int row = 0;
        for (Key key : keys) {
            if (scene.hiddenLegendItems.contains(key.id)) continue;
            String value = shorten(scene.legendLabels.getOrDefault(key.id, key.text), width - sample - padding);
            float used = sample + padding / 2 + measure(value);
            if (x > 0 && x + used > width) {
                row++;
                x = 0;
            }
            placed.add(new PlacedKey(new Key(key.id, value, key.color, key.size, key.line, key.arrow), x + padding, row));
            x += used + 2 * padding;
        }
        return placed;
    }

    private void write(XMLStreamWriter writer, double widthMm) throws XMLStreamException {
        var svg = new Svg(writer);
        float titleHeight = padding + fontSize * 1.9f;
        float pageWidth = width + 2 * padding;
        float step = fontSize * 1.52f;
        float sample = fontSize * 1.9f;
        var keyLayout = layoutKeys(sample);
        int rows = keyLayout.isEmpty() ? 0 : keyLayout.get(keyLayout.size() - 1).row + 1;
        float keyHeight = rows == 0 ? 0 : step * rows + padding * .45f;
        boolean topLegend = scene.legendPosition.equals("top");
        float header = titleHeight + (topLegend ? keyHeight : 0);
        float footer = header + height + padding * .8f;
        float legendTop = topLegend ? titleHeight : footer;
        float notesTop = footer + (topLegend ? 0 : keyHeight);
        float pageHeight = notesTop + step * notes.size() + padding * .68f;
        writer.writeStartDocument("UTF-8", "1.0");
        svg.start("svg", "width", n(widthMm) + "mm", "height", n(widthMm * pageHeight / pageWidth) + "mm",
            "viewBox", "0 0 " + n(pageWidth) + " " + n(pageHeight), "font-family", "Microsoft YaHei, sans-serif",
            "font-size", n(fontSize));
        writer.writeDefaultNamespace(SVG_NS);
        svg.start("title"); svg.text(title); svg.end();
        svg.start("desc"); svg.text(String.join("。", notes)); svg.end();
        svg.start("defs");
        svg.start("clipPath", "id", "plot-clip");
        svg.empty("rect", "width", width, "height", height);
        svg.end();
        svg.end();
        svg.empty("rect", "id", "page-background", "width", n(pageWidth), "height", n(pageHeight), "fill", "#ffffff");
        svg.label(shorten(title, width / 1.52f), padding, padding + fontSize * 1.3f, fontSize * 1.48f, "#172033", "font-weight", "bold");
        svg.start("g", "id", "network", "transform", "translate(" + n(padding) + " " + n(header) + ")",
            "clip-path", "url(#plot-clip)");
        svg.empty("rect", "id", "network-background", "width", width, "height", height, "fill", dark ? "#090e17" : "#ffffff");
        if (scene.geographyEnabled) writeMap(svg);
        writeEdges(svg);
        writeNodes(svg);
        svg.start("g", "id", "label-connectors", "stroke", dark ? "#ffc090" : "#a34121", "stroke-width", "1");
        for (Label label : labels) {
            if (!label.connector) continue;
            double endX = Math.max(label.box.getMinX(), Math.min(label.box.getMaxX(), label.anchorX));
            double endY = Math.max(label.box.getMinY(), Math.min(label.box.getMaxY(), label.anchorY));
            svg.empty("line", "x1", n(label.anchorX), "y1", n(label.anchorY), "x2", n(endX), "y2", n(endY));
        }
        svg.end();
        svg.start("g", "id", "labels");
        for (Label label : labels) {
            String ink = label.priority ? (dark ? "#ffd2a7" : "#7d2c15") : dark ? "#f4f6fa" : "#344b5b";
            svg.label(label.text, label.x, label.y, label.size, ink, "data-label-for", label.id,
                "font-weight", label.priority ? "bold" : "normal", "paint-order", "stroke fill",
                "stroke", dark ? "#090e17" : "#ffffff", "stroke-width", n(fontSize * .27),
                "stroke-linejoin", "round");
        }
        svg.end();
        svg.end();
        writeLegend(svg, legendTop, step, sample, keyLayout);
        svg.start("g", "id", "notes");
        for (int i = 0; i < notes.size(); i++) {
            svg.label(shorten(notes.get(i), width / .9f), padding, notesTop + i * step + fontSize,
                fontSize * .9f, "#465c69");
        }
        svg.end();
        svg.end();
        writer.writeEndDocument();
        writer.flush();
    }

    private void writeEdges(Svg svg) throws XMLStreamException {
        svg.start("g", "id", "edges", "fill", "none", "stroke-linecap", "round");
        for (Edge edge : edges) {
            var shape = edge.shape;
            float t = shape.finish();
            String geometry = "M " + xy(shape.ax(), shape.ay());
            if (shape.curved()) {
                geometry += " Q " + xy(shape.ax() + t * (shape.cx() - shape.ax()), shape.ay() + t * (shape.cy() - shape.ay()));
            } else geometry += " L";
            geometry += " " + xy(shape.x(t), shape.y(t));
            svg.empty("path", "d", geometry, "stroke", rgb(edge.style.color()), "stroke-width", n(edge.style.width()),
                "opacity", n(edge.style.alpha()), "data-edge-id", edge.link.id, "data-weight", edge.link.weight,
                "data-focus", edge.focus,
                "data-source", scene.nodes.get(edge.link.source).id, "data-target", scene.nodes.get(edge.link.target).id);
        }
        svg.end();
        svg.start("g", "id", "arrowheads");
        for (Edge edge : edges) {
            var arrow = edge.shape.arrow();
            if (arrow == null) continue;
            svg.empty("polygon", "points", xy(arrow.tipX(), arrow.tipY()) + " " + xy(arrow.leftX(), arrow.leftY())
                + " " + xy(arrow.rightX(), arrow.rightY()), "fill", rgb(edge.style.color()),
                "opacity", n(Math.min(1, edge.style.alpha() * 1.4)), "data-edge-id", edge.link.id);
        }
        svg.end();
    }

    private void writeNodes(Svg svg) throws XMLStreamException {
        svg.start("g", "id", "nodes");
        var ordered = focusActive ? visible.stream().sorted(Comparator
            .comparing((DiagramRenderer.Node n) -> priorityIds.contains(n.vertex().id))
            .thenComparing(n -> relatedIds.contains(n.vertex().id))).toList() : visible;
        float contextAlpha = Math.max(.17f, Math.min(.55f, (float) (9 / Math.sqrt(visible.size() + 1))));
        for (var node : ordered) {
            boolean priority = priorityIds.contains(node.vertex().id);
            boolean related = relatedIds.contains(node.vertex().id);
            int color = priority ? DiagramRenderer.ACCENT : scene.colors().active() ? node.rgb() : related ? FOCUS_INK
                : focusActive ? CONTEXT_INK : node.rgb();
            float radius = priority ? Math.max(node.radius(), 4.6f * projection.unit())
                : focusActive && !related ? node.radius() * .73f : node.radius();
            float alpha = priority || related ? 1 : focusActive ? Math.min(contextAlpha, node.alpha()) : node.alpha();
            svg.start("circle", "cx", n(node.x()), "cy", n(node.y()), "r", n(radius),
                "fill", rgb(color), "opacity", n(alpha), "stroke", priority ? "#7d2c15" : dark ? "#0c1621" : "#475569",
                "stroke-width", n(priority ? 1.3f * projection.unit() : Math.min(1.5, radius * .2)),
                "data-node-id", node.vertex().id,
                "data-community", node.vertex().community);
            svg.start("title"); svg.text(node.vertex().label); svg.end();
            svg.end();
        }
        svg.end();
    }

    private void writeMap(Svg svg) throws XMLStreamException {
        svg.start("g", "id", "map");
        for (double[][][] polygon : scene.geography.polygons) {
            StringBuilder outline = new StringBuilder();
            for (double[][] ring : polygon) outline.append(projectPath(ring, true));
            if (!outline.isEmpty()) svg.empty("path", "d", outline, "fill", "#f1f4f5", "stroke", "#b0bfc5", "fill-rule", "evenodd");
        }
        for (double[][] line : scene.geography.lines) {
            String outline = projectPath(line, false);
            if (!outline.isBlank()) svg.empty("path", "d", outline, "fill", "none", "stroke", "#b0bfc5");
        }
        svg.end();
    }

    private String projectPath(double[][] path, boolean ring) {
        Matrix4f matrix = projection.matrix();
        StringBuilder output = new StringBuilder();
        Vector4f previous = null;
        boolean started = false;
        for (double[] point : path) {
            Vector4f current = new Vector4f((float) point[0], (float) point[1], 0, 1).mul(matrix);
            boolean in = current.z + current.w >= 0;
            if (previous != null) {
                float start = previous.z + previous.w;
                if ((start >= 0) != in) {
                    float t = start / (start - current.z - current.w);
                    Vector4f crossing = new Vector4f(previous).lerp(current, t);
                    output.append(started ? " L " : " M ").append(projectedPoint(crossing));
                    started = ring || in;
                }
            }
            if (in) {
                output.append(started ? " L " : " M ").append(projectedPoint(current));
                started = true;
            }
            previous = current;
        }
        if (ring && !output.isEmpty()) output.append(" Z");
        return output.toString();
    }

    private String projectedPoint(Vector4f point) {
        return xy((point.x / point.w + 1) * width / 2, (1 - point.y / point.w) * height / 2);
    }

    private void writeLegend(Svg svg, float top, float step, float sample, List<PlacedKey> placed) throws XMLStreamException {
        svg.start("g", "id", "legend", "data-position", scene.legendPosition);
        for (PlacedKey item : placed) {
            Key key = item.key;
            float x = item.x;
            float y = top + item.row * step + fontSize;
            float middle = y - fontSize * .34f;
            if (key.line) {
                svg.empty("line", "x1", n(x), "y1", n(middle), "x2", n(x + sample), "y2", n(middle),
                    "stroke", rgb(key.color), "stroke-width", n(key.size));
                if (key.arrow) svg.empty("polygon", "fill", rgb(key.color), "points", xy(x + sample, middle) + " "
                    + xy(x + sample - fontSize * .5f, middle - fontSize * .23f) + " "
                    + xy(x + sample - fontSize * .5f, middle + fontSize * .23f));
            } else svg.empty("circle", "cx", n(x + sample / 2), "cy", n(middle), "r", n(fontSize * .29f), "fill", rgb(key.color));
            svg.label(key.text, x + sample + padding / 2, y, fontSize, "#172033", "data-legend-item", key.id);
        }
        svg.end();
    }

    private float measure(String value) {
        return (float) font.getStringBounds(value, FONT_CONTEXT).getWidth();
    }

    private String shorten(String value, float available) {
        String text = value.replaceAll("\\s+", " ").trim();
        if (measure(text) <= available) return text;
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int midpoint = (low + high + 1) / 2;
            int end = text.offsetByCodePoints(0, midpoint);
            if (measure(text.substring(0, end) + "…") <= available) low = midpoint;
            else high = midpoint - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + "…";
    }

    private static String weight(double value) {
        return String.format(Locale.ROOT, "%.4g", value).replaceFirst("(\\.\\d*?)0+(?=e|$)", "$1")
            .replaceFirst("\\.(?=e|$)", "");
    }
    private static String n(double value) { return Double.toString(Math.round(value * 100) / 100.0); }
    private static String xy(double x, double y) { return n(x) + "," + n(y); }
    private static String rgb(int value) { return "#" + Integer.toHexString(value | 0x1000000).substring(1); }

    private record Svg(XMLStreamWriter out) {
        void start(String element, Object... attributes) throws XMLStreamException { out.writeStartElement(element); attrs(attributes); }
        void empty(String element, Object... attributes) throws XMLStreamException { out.writeEmptyElement(element); attrs(attributes); }
        void end() throws XMLStreamException { out.writeEndElement(); }
        void text(String value) throws XMLStreamException { out.writeCharacters(xml(value)); }
        void label(String value, float x, float y, float size, String color, Object... extra) throws XMLStreamException {
            start("text", "x", n(x), "y", n(y), "font-size", n(size), "fill", color);
            attrs(extra);
            text(value);
            end();
        }
        private void attrs(Object... pairs) throws XMLStreamException {
            for (int i = 0; i < pairs.length; i += 2) {
                out.writeAttribute(pairs[i].toString(), xml(String.valueOf(pairs[i + 1])));
            }
        }
        private static String xml(String value) {
            StringBuilder clean = new StringBuilder();
            value.codePoints().filter(cp -> cp == 9 || cp == 10 || cp == 13
                || cp >= 32 && cp <= 0xd7ff || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000)
                .forEach(clean::appendCodePoint);
            return clean.toString();
        }
    }
}
