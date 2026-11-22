package org.gephi.plugins.explorer3d;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.StringWriter;
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
    private record EdgeScale(float x, int row, List<PlacedKey> keys) { }
    private record LegendLayout(List<PlacedKey> keys, EdgeScale scale, int rows) { }
    private static final String SCALE_TITLE = "线宽（边权）";
    private static final float LEGEND_TEXT = .95f;
    private static final float CAPTION_TEXT = .9f;

    private final Scene scene;
    private final DiagramRenderer.Projection projection;
    private final List<DiagramRenderer.Node> visible;
    private final List<DiagramRenderer.Node> focusObstacles;
    private final List<Edge> edges = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();
    private final Set<String> priorityIds;
    private final Set<String> labeledIds;
    private final Set<String> relatedIds = new LinkedHashSet<>();
    private final boolean focusActive;
    private final int width, height;
    private final float fontSize, padding;
    private final Font font;
    private final boolean dark;
    private final String title, direction;
    private final PaperOptions options;
    private int unplacedPriority, drawnArrows, focusEdges, drawnFocusEdges, unlocatedFocusEdges;
    private int mapEligible, mapLocated;

    record Document(String svg, double widthMm, double heightMm, List<Key> keys, Map<String, Object> metrics) {
        Map<String, Object> save(String file) {
            Path output = Path.of(file);
            if (!output.isAbsolute() || !file.toLowerCase(Locale.ROOT).endsWith(".svg"))
                throw new IllegalArgumentException("An absolute .svg path is required");
            if (Files.exists(output)) throw new IllegalArgumentException("Output exists; choose a new file name");
            try {
                Path temporary = Files.createTempFile(output.getParent(), ".gephi-paper-", ".tmp");
                try {
                    Files.writeString(temporary, svg);
                    Files.move(temporary, output);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException ex) {
                throw new IllegalStateException("SVG export failed: " + ex.getMessage(), ex);
            }
            var result = new LinkedHashMap<>(metrics);
            result.put("success", true);
            result.put("path", file);
            return result;
        }
    }

    static Document render(Scene snapshot, int width, int height, double widthMm,
                           String title, String direction, PaperOptions options) {
        if (width < 300 || width > 8000 || height < 200 || height > 8000 ||
            !Double.isFinite(widthMm) || widthMm < 60 || widthMm > 300) {
            throw new IllegalArgumentException("View size must be 300–8000 by 200–8000, with a paper width of 60–300 mm");
        }
        Scene scene = snapshot.copy();
        scene.nodeScale *= options.nodeScale();
        scene.edgeWidth *= options.edgeScale();
        scene.arrowScale *= options.arrowScale();
        if (options.arrows() != null && options.arrows() != scene.showsArrows()) {
            scene.arrows = options.arrows();
            scene.arrowsManuallySet = true;
        }
        var known = new LinkedHashSet<String>();
        for (Scene.Vertex node : scene.nodes) known.add(node.id);
        var unknown = options.marks().keySet().stream().filter(id -> !known.contains(id)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("找不到标注节点 " + String.join("、", unknown));
        float pointSize = (float) (options.fontPoints() * 25.4 / (widthMm * 72));
        float paperFont = width * pointSize / Math.max(.5f, 1 - 2.4f * pointSize);
        int gap = options.paired() ? Math.round(paperFont * 1.8f) : 0;
        int plotWidth = options.paired() ? (width - gap) / 2 : width;
        int plotHeight = options.paired() ? Math.min(height, (int) (width * .45)) : height;
        if (options.paired() && (plotWidth < 100 || plotHeight < 140))
            throw new IllegalArgumentException("组图版面过窄，请加宽成图或减小字号");
        Scene rightScene = null;
        if (options.paired()) {
            var years = EdgeYears.available(scene);
            if (!years.contains(options.leftYear()) || !years.contains(options.rightYear()))
                throw new IllegalArgumentException("所选年份缺少边数据。可用年份 " + years);
            rightScene = scene.copy();
            scene.activeYear = options.leftYear();
            rightScene.activeYear = options.rightYear();
            scene.referenceYear = rightScene.referenceYear = 0;
            scene.refreshViewCaches();
            rightScene.refreshViewCaches();
            var right = rightScene;
            var positions = scene.nodes.stream().filter(n -> scene.visible(n) || right.visible(n))
                .map(n -> position(scene, n)).toList();
            var matrix = GraphRenderer.matrix(scene, plotWidth, plotHeight);
            if (outside(positions, matrix, plotWidth, plotHeight, Math.min(paperFont * 1.4f, plotWidth * .12f)))
                GraphRenderer.frame(scene, positions, plotWidth, plotHeight);
            rightScene.restoreCamera(scene.cameraValues());
        }
        var first = new PaperSvg(scene, plotWidth, plotHeight, paperFont, title, direction, true, options);
        var second = rightScene == null ? null
            : new PaperSvg(rightScene, plotWidth, plotHeight, paperFont, title, direction, true, options);
        var buffer = new StringWriter();
        try {
            return first.write(XMLOutputFactory.newFactory().createXMLStreamWriter(buffer), buffer, widthMm, second, gap);
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("SVG composition failed: " + ex.getMessage(), ex);
        }
    }

    private PaperSvg(Scene scene, int width, int height, float paperFont, String title, String direction,
                     boolean includeLabels, PaperOptions options) {
        this.scene = scene;
        this.width = width;
        this.height = height;
        this.options = options;
        fontSize = paperFont;
        font = FONT.deriveFont(fontSize);
        padding = fontSize * 1.2f;
        dark = scene.dark && !scene.geographyEnabled;
        this.direction = direction.isBlank() ? defaultDirection(scene) : direction;
        this.title = title.isBlank()
            ? (scene.geographyEnabled ? "企业地理关系图" : "网络关系图") : title;
        priorityIds = new LinkedHashSet<>(scene.selection);
        if (scene.geography != null) priorityIds.addAll(scene.geography.leaders);
        labeledIds = new LinkedHashSet<>(priorityIds);
        labeledIds.addAll(options.marks().keySet());
        focusActive = !priorityIds.isEmpty() || !scene.highlight.isEmpty() || !scene.pathEdges.isEmpty();
        if (!options.paired() && (!labeledIds.isEmpty() || focusActive)) frameFocus();
        projection = DiagramRenderer.project(scene, width, height, true, options.leftYear(), options.rightYear());
        visible = Arrays.stream(projection.nodes()).filter(node -> node != null &&
            node.x() + node.radius() >= 0 && node.x() - node.radius() <= width &&
            node.y() + node.radius() >= 0 && node.y() - node.radius() <= height)
            .sorted(Comparator.comparingDouble(DiagramRenderer.Node::z).reversed()).toList();
        prepareEdges();
        focusObstacles = visible.stream().filter(node -> labeledIds.contains(node.vertex().id)
            || relatedIds.contains(node.vertex().id)).toList();
        if (includeLabels) prepareLabels();
        if (scene.geographyEnabled) for (Scene.Vertex node : scene.nodes) {
            if (!scene.passesFilters(node)) continue;
            mapEligible++;
            if (scene.located(node)) mapLocated++;
        }
        prepareLegend();
    }

    private boolean isFocusEdge(Scene.Link link) {
        if (scene.directedScope()) return scene.supplyScope().edges().contains(link.id);
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
        Set<String> endpoints = new LinkedHashSet<>(labeledIds);
        endpoints.addAll(scene.highlight);
        for (Scene.Link link : scene.edges) {
            Scene.Vertex from = scene.nodes.get(link.source);
            Scene.Vertex to = scene.nodes.get(link.target);
            if (isFocusEdge(link) && scene.visible(link)) {
                endpoints.add(from.id);
                endpoints.add(to.id);
            }
        }
        var matrix = GraphRenderer.matrix(scene, width, height);
        var positions = scene.nodes.stream().filter(node -> endpoints.contains(node.id) && scene.visible(node))
            .map(node -> position(scene, node)).toList();
        if (outside(positions, matrix, width, height, Math.min(fontSize * 2, width * .15f)))
            GraphRenderer.frame(scene, positions, width, height);
    }

    private static double[] position(Scene scene, Scene.Vertex node) {
        return scene.geographyEnabled ? scene.geography.positions.get(node.id) : new double[]{node.x, node.y, node.z};
    }

    private static boolean outside(List<double[]> positions, Matrix4f matrix, int width, int height, float margin) {
        return positions.stream().anyMatch(p -> {
            float[] point = GraphRenderer.project(matrix, p[0], p[1], p[2], width, height);
            return point == null || point[0] < margin || point[0] > width - margin
                || point[1] < margin || point[1] > height - margin || Math.abs(point[2]) > 1;
        });
    }

    static String defaultDirection(Scene scene) {
        if (scene.geography != null && scene.geography.direction != null && !scene.geography.direction.isBlank()) {
            return scene.geography.direction;
        }
        return scene.source.toLowerCase(Locale.ROOT).contains("suppliers_to_customers")
            ? "供应商 → 客户" : "起点 → 终点";
    }

    static List<Key> legendItems(Scene snapshot, int width, int height, double widthMm, String direction) {
        float size = (float) (width * 6.8 * 25.4 / (widthMm * 72));
        return List.copyOf(new PaperSvg(snapshot, width, height, size, "", direction, false, PaperOptions.DEFAULT).keys);
    }

    private void prepareEdges() {
        float contextAlpha = Math.max(.05f, Math.min(.17f, (float) (3.5 / Math.sqrt(scene.edges.size() + 1))));
        for (Scene.Link link : scene.edges) {
            if (!scene.passesEdge(link)) continue;
            Scene.Vertex source = scene.nodes.get(link.source), target = scene.nodes.get(link.target);
            if (!scene.passesFilters(source) || !scene.passesFilters(target)) continue;
            boolean focus = focusActive && isFocusEdge(link);
            if (!focus && options.contextOpacity() == 0) continue;
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
            int comparison = EdgeYears.changeColor(scene, link, style.color());
            if (comparison != style.color()) style = new DiagramRenderer.EdgeStyle(comparison, .9f, style.width(),
                style.curved(), style.arrowLength());
            if (!focus && options.contextOpacity() >= 0) {
                style = new DiagramRenderer.EdgeStyle(style.color(), (float) options.contextOpacity(), style.width(),
                    style.curved(), style.arrowLength());
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
                .thenComparing(n -> options.marks().containsKey(n.vertex().id))
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
            boolean priority = labeledIds.contains(node.vertex().id);
            if (!priority && (!scene.labels || context >= MAX_CONTEXT_LABELS
                || (focusActive || scene.geographyEnabled) && !relatedIds.contains(node.vertex().id))) continue;
            String label = options.marks().getOrDefault(node.vertex().id, "");
            if (label.isBlank()) label = node.vertex().label.isBlank() ? node.vertex().id : node.vertex().label;
            String text = shorten(label, width * .38f);
            float radius = priority ? Math.max(node.radius(), 4.6f * projection.unit()) : node.radius();
            if (placeLabel(node.vertex().id, text, node.x(), node.y(), radius, node, priority)) {
                if (priority) placedPriority++;
                else context++;
            }
        }
        unplacedPriority = labeledIds.size() - placedPriority;
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
            String selectedLabel = scene.directedScope() ? "中心节点" : "已选节点";
            String description = leaders && selected ? "链主 / " + selectedLabel : leaders ? "链主" : selectedLabel;
            keys.add(new Key("selection", description, DiagramRenderer.ACCENT, 0, false, false));
        }
        if (edges.stream().anyMatch(edge -> edge.focus && EdgeYears.changeColor(scene, edge.link, FOCUS_INK) == FOCUS_INK)) {
            keys.add(new Key("focus", "重点关系", FOCUS_INK, fontSize * .2f, true, false));
        }
        if (scene.referenceYear != 0) {
            if (edges.stream().anyMatch(e -> EdgeYears.change(scene, e.link).equals("added")))
                keys.add(new Key("year_added", "仅 " + scene.activeYear + " 年记录", EdgeYears.ADDED, fontSize * .18f, true, false));
            if (edges.stream().anyMatch(e -> EdgeYears.change(scene, e.link).equals("removed")))
                keys.add(new Key("year_removed", "仅 " + scene.referenceYear + " 年记录", EdgeYears.REMOVED, fontSize * .18f, true, false));
        }
        if (scene.colors().active()) {
            addFieldKeys(scene.colors());
        } else if (!focusActive && projection.weighted()) {
            keys.add(new Key("weighted_nodes", "节点色深 · " + (projection.maxWeight() > 0 ? "连接边权合计" : "度数"), 0x14604d, 0, false, false));
        } else if (!focusActive && !projection.classic()) {
            addCommunityKeys();
        }
        if (drawnArrows > 0) keys.add(new Key("direction", direction, CONTEXT_INK, fontSize * .1f, true, true));
        if (!projection.classic() && !edges.isEmpty()) {
            List<Edge> samples = drawnFocusEdges > 0 ? edges.stream().filter(Edge::focus).toList() : edges;
            var byWeight = Comparator.comparingDouble((Edge e) -> EdgeYears.weight(scene, e.link));
            Edge low = samples.stream().min(byWeight).orElseThrow();
            Edge high = samples.stream().max(byWeight).orElseThrow();
            if (byWeight.compare(low, high) != 0) {
                int ink = drawnFocusEdges > 0 ? FOCUS_INK : CONTEXT_INK;
                keys.add(new Key("weight_low", "边权 " + weight(EdgeYears.weight(scene, low.link)), ink, low.style.width(), true, false));
                keys.add(new Key("weight_high", "边权 " + weight(EdgeYears.weight(scene, high.link)), ink, high.style.width(), true, false));
            }
        }
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

    private LegendLayout layoutKeys(float sample, int contentWidth, List<Key> legend) {
        List<PlacedKey> placed = new ArrayList<>();
        List<Key> shown = legend.stream().filter(key -> !scene.hiddenLegendItems.contains(key.id)).toList();
        List<Key> weights = shown.stream().filter(PaperSvg::weightKey).map(key -> {
            String text = scene.legendLabels.getOrDefault(key.id, key.text.replaceFirst("^边权 ", ""));
            return new Key(key.id, text, key.color, key.size, key.line, key.arrow);
        }).toList();
        float markerGap = fontSize * .48f;
        float itemGap = fontSize * 1.35f;
        float labelStart = measure(SCALE_TITLE) + markerGap * 2;
        float scaleWidth = labelStart + (float) weights.stream().mapToDouble(key -> sample + markerGap
            + measure(key.text) * LEGEND_TEXT).sum() + markerGap * 1.5f;
        boolean groupWeights = weights.size() == 2 && scaleWidth <= contentWidth;
        float x = 0;
        int row = 0;
        for (Key key : shown) {
            if (groupWeights && weightKey(key)) continue;
            String value = shorten(scene.legendLabels.getOrDefault(key.id, key.text),
                (contentWidth - sample - markerGap) / LEGEND_TEXT);
            float used = sample + markerGap + measure(value) * LEGEND_TEXT;
            if (x > 0 && x + used > contentWidth) {
                row++;
                x = 0;
            }
            placed.add(new PlacedKey(new Key(key.id, value, key.color, key.size, key.line, key.arrow), x + padding, row));
            x += used + itemGap;
        }
        EdgeScale scale = null;
        if (groupWeights) {
            if (x > 0 && x + scaleWidth > contentWidth) { row++; x = 0; }
            float start = padding + (x > 0 ? contentWidth - scaleWidth : 0);
            float symbolX = start + labelStart;
            var examples = new ArrayList<PlacedKey>();
            for (Key weight : weights) {
                examples.add(new PlacedKey(weight, symbolX, row));
                symbolX += sample + markerGap * 2.5f + measure(weight.text) * LEGEND_TEXT;
            }
            scale = new EdgeScale(start, row, examples);
        }
        return new LegendLayout(placed, scale, shown.isEmpty() ? 0 : row + 1);
    }

    private static boolean weightKey(Key key) {
        return key.id.equals("weight_low") || key.id.equals("weight_high");
    }

    private List<Key> combinedLegend(PaperSvg second) {
        if (second == null) return List.copyOf(keys);
        var combined = new LinkedHashMap<String, Key>();
        for (PaperSvg panel : List.of(this, second)) for (Key key : panel.keys) {
            if (!weightKey(key)) combined.putIfAbsent(key.id, key);
        }
        var categories = combined.values().stream()
            .filter(key -> key.id.startsWith("community:") || key.id.contains(":category:")).toList();
        if (categories.size() > 6 || combined.containsKey("communities")
            || combined.keySet().stream().anyMatch(key -> key.startsWith("field:") && key.endsWith(":summary"))) {
            var summary = combined.values().stream().filter(key -> key.id.equals("communities")
                || key.id.startsWith("field:") && key.id.endsWith(":summary")).findFirst();
            categories.forEach(key -> combined.remove(key.id));
            summary.ifPresent(key -> combined.remove(key.id));
            String field = scene.colors().active() ? scene.colors().field() : "社区";
            String id = summary.map(Key::id).orElse(scene.colors().active() ? "field:" + field + ":summary" : "communities");
            combined.put(id, new Key(id, "颜色按" + field + "区分", CONTEXT_INK, 0, false, false));
        }
        if (!projection.classic()) {
            for (boolean focus : new boolean[]{true, false}) {
                var weights = new java.util.TreeMap<Double, Edge>();
                for (PaperSvg panel : List.of(this, second)) for (Edge edge : panel.edges)
                    if (edge.focus == focus) weights.put(EdgeYears.weight(panel.scene, edge.link), edge);
                if (weights.size() > 1) {
                    for (var example : List.of(weights.firstEntry(), weights.lastEntry())) {
                        String id = example.getKey().equals(weights.firstKey()) ? "weight_low" : "weight_high";
                        var style = example.getValue().style;
                        combined.put(id, new Key(id, "边权 " + weight(example.getKey()), style.color(), style.width(), true, false));
                    }
                    break;
                }
            }
        }
        return List.copyOf(combined.values());
    }

    private List<String> caption(PaperSvg second, int contentWidth) {
        var description = new ArrayList<String>();
        if (options.automaticCaption()) {
            var scope = new ArrayList<String>();
            if (scene.directedScope()) {
                scope.add("中心 " + String.join("、", scene.neighborhoodRoots));
                scope.add(scene.neighborhoodHops + " 阶" + SupplyScope.directionLabel(scene.neighborhoodDirection));
            }
            if (scene.minWeight > -1e100) scope.add("边权 ≥ " + weight(scene.minWeight));
            if (second == null) {
                if (scene.activeYear != 0) scope.add(scene.referenceYear == 0 ? scene.activeYear + " 年"
                    : scene.referenceYear + " → " + scene.activeYear + " 年");
                scope.add(figureCounts());
                description.add(String.join(" · ", scope));
            } else {
                if (!scope.isEmpty()) description.add(String.join(" · ", scope));
                for (PaperSvg panel : List.of(this, second)) {
                    description.add(panel.scene.activeYear + " 年 · " + panel.figureCounts()
                        + (scene.geographyEnabled ? " · " + panel.mapCoverage() + panel.unlocatedNote() : ""));
                }
            }
            if (scene.geographyEnabled) {
                String mapNote = "底图 " + scene.geography.source;
                String reference = scene.geography.locationReference;
                if (scene.activeYear != 0 && reference != null && !reference.isBlank() && !mapNote.contains(reference))
                    mapNote += " · " + reference;
                description.add(second == null ? mapCoverage() + unlocatedNote() + " · " + mapNote : mapNote);
            }
            if (drawnArrows == 0 && (second == null || second.drawnArrows == 0) && (hasDirectedEdges()
                || second != null && second.hasDirectedEdges()))
                description.add("关系方向 · " + direction + "（未显示箭头）");
            if (scene.synthetic) description.add("合成示例，非真实观测");
        }
        if (!options.weightMeaning().isBlank()) description.add("边权 · " + options.weightMeaning());
        if (!options.caption().isBlank()) description.addAll(options.caption().lines().toList());
        var wrapped = new ArrayList<String>();
        for (String paragraph : description) {
            String line = paragraph.strip();
            while (measure(line) * CAPTION_TEXT > contentWidth) {
                int count = line.codePointCount(0, line.length());
                int end = line.offsetByCodePoints(0, Math.max(1, (int) (count * contentWidth / (measure(line) * CAPTION_TEXT))));
                while (measure(line.substring(0, end)) * CAPTION_TEXT > contentWidth
                    && line.codePointCount(0, end) > 1)
                    end = line.offsetByCodePoints(end, -1);
                int space = line.lastIndexOf(' ', end);
                if (space > end * .7) end = space;
                wrapped.add(line.substring(0, end).stripTrailing());
                line = line.substring(end).stripLeading();
            }
            wrapped.add(line);
        }
        return wrapped;
    }

    private String mapCoverage() {
        return "地图定位 " + mapLocated + " / " + mapEligible + " 个节点";
    }

    private String figureCounts() {
        return "入图 " + visible.size() + " 个节点、" + edges.size() + " 条关系";
    }

    private String unlocatedNote() {
        return unlocatedFocusEdges == 0 ? "" : " · " + unlocatedFocusEdges + " 条重点关系缺少城市定位";
    }

    private boolean hasDirectedEdges() { return edges.stream().anyMatch(edge -> edge.link.directed); }

    private Document write(XMLStreamWriter writer, StringWriter buffer, double widthMm, PaperSvg second, int gap)
            throws XMLStreamException {
        var svg = new Svg(writer);
        int contentWidth = second == null ? width : width * 2 + gap;
        var legend = combinedLegend(second);
        var caption = caption(second, contentWidth);
        float titleHeight = padding + fontSize * 1.9f;
        float pageWidth = contentWidth + 2 * padding;
        float step = fontSize * 1.48f;
        float noteStep = fontSize * 1.35f;
        float sample = fontSize * 1.65f;
        var keyLayout = layoutKeys(sample, contentWidth, legend);
        float keyHeight = keyLayout.rows == 0 ? 0 : step * keyLayout.rows + fontSize * .23f;
        boolean topLegend = scene.legendPosition.equals("top");
        float panelTitle = second == null ? 0 : fontSize * 1.55f;
        float header = titleHeight + (topLegend ? keyHeight : 0) + panelTitle;
        float footer = header + height + padding * .8f;
        float legendTop = topLegend ? titleHeight : footer;
        float notesTop = footer + (topLegend ? 0 : keyHeight);
        float pageHeight = notesTop + noteStep * caption.size() + padding * .6f;
        double heightMm = widthMm * pageHeight / pageWidth;
        writer.writeStartDocument("UTF-8", "1.0");
        svg.start("svg", "width", n(widthMm) + "mm", "height", n(heightMm) + "mm",
            "viewBox", "0 0 " + n(pageWidth) + " " + n(pageHeight), "font-family", "Microsoft YaHei, sans-serif",
            "font-size", n(fontSize));
        writer.writeDefaultNamespace(SVG_NS);
        svg.start("title"); svg.text(title); svg.end();
        svg.start("desc"); svg.text(String.join("\n", caption)); svg.end();
        var state = scene.status();
        var settings = new LinkedHashMap<String,Object>();
        for (String key : List.of("source", "synthetic", "selection", "filters", "hidden_layers", "layer_attribute",
            "node_colors", "style", "camera", "paper_legend", "years", "geography", "supply_scope")) {
            if (state.containsKey(key)) settings.put(key, state.get(key));
        }
        settings.put("paper_options", options);
        settings.put("paper_diagnostics", second == null ? List.of(paperDiagnostics())
            : List.of(paperDiagnostics(), second.paperDiagnostics()));
        if (second != null) settings.put("panels", List.of(panelMetrics(), second.panelMetrics()));
        svg.start("metadata", "id", "network-view"); svg.text(Scene.JSON.toJson(settings)); svg.end();
        svg.start("defs");
        svg.start("clipPath", "id", "plot-clip", "clipPathUnits", "userSpaceOnUse");
        svg.empty("rect", "width", width, "height", height);
        svg.end();
        svg.end();
        svg.empty("rect", "id", "page-background", "width", n(pageWidth), "height", n(pageHeight), "fill", "#ffffff");
        svg.label(shorten(title, contentWidth / 1.52f), padding, padding + fontSize * 1.3f, fontSize * 1.48f, "#172033", "font-weight", "bold");
        if (second == null) writeNetwork(svg, padding, header, "");
        else {
            writePanel(svg, "a", padding, header);
            second.writePanel(svg, "b", padding + width + gap, header);
        }
        if (keyLayout.rows > 0) drawFooterRule(svg, "legend-rule", legendTop, contentWidth);
        else if (!caption.isEmpty()) drawFooterRule(svg, "caption-rule", footer, contentWidth);
        writeLegend(svg, legendTop, step, sample, keyLayout);
        if (!caption.isEmpty()) {
            svg.start("g", "id", "notes");
            float noteBaseline = notesTop + fontSize * 1.1f;
            for (int i = 0; i < caption.size(); i++)
                svg.label(caption.get(i), padding, noteBaseline + i * noteStep, fontSize * CAPTION_TEXT, "#49596a");
            svg.end();
        }
        svg.end();
        writer.writeEndDocument();
        writer.flush();
        writer.close();
        var report = new LinkedHashMap<>(panelMetrics());
        report.put("workspace_id", scene.workspaceId);
        report.put("revision", scene.revision);
        if (second != null) {
            var secondMetrics = second.panelMetrics();
            report.remove("year");
            report.put("panels", List.of(panelMetrics(), secondMetrics));
            for (String key : List.of("shown_nodes", "shown_edges", "shown_arrows", "labels", "unplaced_priority_labels", "priority_labels"))
                report.put(key, (int) report.get(key) + (int) secondMetrics.get(key));
        }
        return new Document(buffer.toString(), widthMm, heightMm, legend, Map.copyOf(report));
    }

    private Map<String, Object> panelMetrics() {
        return Map.of("year", scene.activeYear, "shown_nodes", visible.size(), "shown_edges", edges.size(),
            "shown_arrows", drawnArrows, "labels", labels.size(), "unplaced_priority_labels", unplacedPriority,
            "priority_labels", labeledIds.size());
    }

    private Map<String, Object> paperDiagnostics() {
        return Map.of("year", scene.activeYear, "all_nodes", scene.nodes.size(), "all_edges", scene.edges.size(),
            "focused_edges", focusEdges, "drawn_focused_edges", drawnFocusEdges,
            "unlocated_focused_edges", unlocatedFocusEdges, "unplaced_priority_labels", unplacedPriority,
            "map_enabled", scene.geographyEnabled, "map_eligible", mapEligible, "map_located", mapLocated);
    }

    private void writePanel(Svg svg, String letter, float x, float y) throws XMLStreamException {
        svg.start("g", "id", "panel-" + letter, "data-year", scene.activeYear);
        svg.label("(" + letter + ") " + scene.activeYear + " 年", x, y - fontSize * .39f,
            fontSize * 1.09f, "#172033", "font-weight", "bold");
        writeNetwork(svg, x, y, letter + "-");
        svg.end();
    }

    private void writeNetwork(Svg svg, float x, float y, String prefix) throws XMLStreamException {
        svg.start("g", "id", prefix + "network", "transform", "translate(" + n(x) + " " + n(y) + ")",
            "clip-path", "url(#plot-clip)");
        svg.empty("rect", "id", prefix + "network-background", "width", width, "height", height, "fill", dark ? "#090e17" : "#ffffff");
        if (scene.geographyEnabled) writeMap(svg, prefix);
        writeEdges(svg, prefix);
        writeNodes(svg, prefix);
        svg.start("g", "id", prefix + "label-connectors", "stroke", dark ? "#ffc090" : "#a34121", "stroke-width", "1");
        for (Label label : labels) {
            if (!label.connector) continue;
            double endX = Math.max(label.box.getMinX(), Math.min(label.box.getMaxX(), label.anchorX));
            double endY = Math.max(label.box.getMinY(), Math.min(label.box.getMaxY(), label.anchorY));
            svg.empty("line", "x1", n(label.anchorX), "y1", n(label.anchorY), "x2", n(endX), "y2", n(endY));
        }
        svg.end();
        svg.start("g", "id", prefix + "labels");
        for (Label label : labels) {
            String ink = label.priority ? (dark ? "#ffd2a7" : "#7d2c15") : dark ? "#f4f6fa" : "#344b5b";
            svg.empty("rect", "x", n(label.box.getX()), "y", n(label.box.getY()),
                "width", n(label.box.getWidth()), "height", n(label.box.getHeight()), "rx", n(fontSize * .14f),
                "fill", dark ? "#090e17" : "#ffffff", "opacity", label.priority ? ".91" : ".78");
            svg.label(label.text, label.x, label.y, label.size, ink, "data-label-for", label.id,
                "font-weight", label.priority ? "bold" : "normal");
        }
        svg.end();
        svg.end();
    }

    private void writeEdges(Svg svg, String prefix) throws XMLStreamException {
        svg.start("g", "id", prefix + "edges", "fill", "none", "stroke-linecap", "round");
        for (Edge edge : edges) {
            var shape = edge.shape;
            float t = shape.finish();
            String geometry = "M " + xy(shape.ax(), shape.ay());
            if (shape.curved()) {
                geometry += " Q " + xy(shape.ax() + t * (shape.cx() - shape.ax()), shape.ay() + t * (shape.cy() - shape.ay()));
            } else geometry += " L";
            geometry += " " + xy(shape.x(t), shape.y(t));
            svg.empty("path", "d", geometry, "stroke", rgb(edge.style.color()), "stroke-width", n(edge.style.width()),
                "opacity", n(edge.style.alpha()), "data-edge-id", edge.link.id, "data-weight", EdgeYears.weight(scene, edge.link),
                "data-focus", edge.focus, "data-year-status", EdgeYears.change(scene, edge.link),
                "data-source", scene.nodes.get(edge.link.source).id, "data-target", scene.nodes.get(edge.link.target).id);
        }
        svg.end();
        svg.start("g", "id", prefix + "arrowheads");
        for (Edge edge : edges) {
            var arrow = edge.shape.arrow();
            if (arrow == null) continue;
            svg.empty("polygon", "points", xy(arrow.tipX(), arrow.tipY()) + " " + xy(arrow.leftX(), arrow.leftY())
                + " " + xy(arrow.rightX(), arrow.rightY()), "fill", rgb(edge.style.color()),
                "opacity", n(Math.min(1, edge.style.alpha() * 1.4)), "data-edge-id", edge.link.id);
        }
        svg.end();
    }

    private void writeNodes(Svg svg, String prefix) throws XMLStreamException {
        svg.start("g", "id", prefix + "nodes");
        var ordered = focusActive || !options.marks().isEmpty() ? visible.stream().sorted(Comparator
            .comparing((DiagramRenderer.Node n) -> labeledIds.contains(n.vertex().id))
            .thenComparing(n -> relatedIds.contains(n.vertex().id))).toList() : visible;
        float contextAlpha = Math.max(.17f, Math.min(.55f, (float) (9 / Math.sqrt(visible.size() + 1))));
        for (var node : ordered) {
            boolean priority = priorityIds.contains(node.vertex().id);
            boolean related = relatedIds.contains(node.vertex().id);
            boolean marked = !priority && options.marks().containsKey(node.vertex().id);
            int color = priority ? DiagramRenderer.ACCENT : scene.colors().active() ? node.rgb() : related ? FOCUS_INK
                : focusActive ? CONTEXT_INK : node.rgb();
            float radius = priority ? Math.max(node.radius(), 4.6f * projection.unit())
                : focusActive && !related ? node.radius() * .73f : node.radius();
            float alpha = priority || related || marked ? 1 : focusActive ? Math.min(contextAlpha, node.alpha()) : node.alpha();
            if (marked) svg.empty("circle", "cx", n(node.x()), "cy", n(node.y()), "r", n(radius + 2 * projection.unit()),
                "stroke", dark ? "#ffc090" : "#a34121", "stroke-width", n(Math.max(1, projection.unit())),
                "fill", "none", "data-marked-for", node.vertex().id);
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

    private void writeMap(Svg svg, String prefix) throws XMLStreamException {
        svg.start("g", "id", prefix + "map");
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

    private void drawFooterRule(Svg svg, String id, float y, int contentWidth) throws XMLStreamException {
        svg.empty("line", "id", id, "x1", n(padding), "x2", n(padding + contentWidth), "y1", n(y), "y2", n(y),
            "stroke", "#cbd5dc", "stroke-width", n(fontSize * .055f));
    }

    private void writeLegend(Svg svg, float top, float step, float sample, LegendLayout layout) throws XMLStreamException {
        svg.start("g", "id", "legend", "data-position", scene.legendPosition);
        float baseline = top + fontSize * 1.12f;
        for (PlacedKey item : layout.keys)
            writeLegendKey(svg, item, baseline + item.row * step, sample, Math.min(item.key.size, fontSize * .63f));
        if (layout.scale != null) {
            var scale = layout.scale;
            float y = baseline + scale.row * step;
            svg.start("g", "id", "legend-edge-scale");
            svg.label(SCALE_TITLE, scale.x, y, fontSize * .91f, "#49596a", "font-weight", "bold");
            float max = scale.keys.stream().map(item -> item.key.size).max(Float::compare).orElse(1f);
            float ratio = max > 0 ? Math.min(1, fontSize * .63f / max) : 1;
            for (PlacedKey item : scale.keys) writeLegendKey(svg, item, y, sample, item.key.size * ratio);
            svg.end();
        }
        svg.end();
    }

    private void writeLegendKey(Svg svg, PlacedKey item, float baseline, float sample, float stroke) throws XMLStreamException {
        var key = item.key;
        float x = item.x;
        float middle = baseline - fontSize * .34f;
        String ink = rgb(key.color);
        if (key.line) {
            float end = x + sample;
            float tail = key.arrow ? end - fontSize * .36f : end;
            svg.empty("line", "x1", n(x), "y1", n(middle), "x2", n(tail), "y2", n(middle), "stroke", ink,
                "stroke-width", n(Math.max(fontSize * .055f, stroke)), "stroke-linecap", key.arrow ? "butt" : "round");
            if (key.arrow) svg.empty("polygon", "fill", ink, "points", xy(end, middle) + " "
                + xy(end - fontSize * .48f, middle - fontSize * .21f) + " "
                + xy(end - fontSize * .48f, middle + fontSize * .21f));
        } else {
            svg.empty("circle", "cx", n(x + sample * .5f), "cy", n(middle), "r", n(fontSize * .28f), "fill", ink,
                "stroke", key.id.equals("selection") ? "#7d2c15" : "none", "stroke-width", n(fontSize * .065f));
        }
        svg.label(key.text, x + sample + fontSize * .48f, baseline, fontSize * LEGEND_TEXT, "#172033",
            "data-legend-item", key.id);
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
