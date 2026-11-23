package org.gephi.plugins.explorer3d;

import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PaperAudit {
    private static final int DETAIL_LIMIT = 120;

    record Issue(String severity, String code, String panel, String detail) { }
    record Report(String status, int warnings, int errors, List<Issue> issues) { }
    private record TextBox(PaperArtwork.Item item, String value, Rectangle2D ink, double points) { }

    private final List<Issue> issues = new ArrayList<>();
    private int errors, warnings;

    static Report inspect(PaperArtwork art, PaperSvg.Document figure, double minimumPoints) throws IOException {
        if (!Double.isFinite(minimumPoints) || minimumPoints < 4 || minimumPoints > 18)
            throw new IllegalArgumentException("检查字号须在 4–18 pt 之间");
        var audit = new PaperAudit();
        var page = new Rectangle2D.Double(0, 0, art.width, art.height);
        double tolerance = .5 / art.pointsPerUnit;
        var texts = new ArrayList<TextBox>();
        for (var item : art.items) {
            if (!item.name().equals("text")) continue;
            var layout = art.layout(item);
            if (layout == null || layout.getBounds().isEmpty()) continue;
            double x = PaperArtwork.textX(item, layout.getAdvance());
            double y = item.point("x", "y").getY();
            var ink = AffineTransform.getTranslateInstance(x, y).createTransformedShape(layout.getBounds()).getBounds2D();
            String text = item.element().getTextContent();
            double points = item.number("font-size", 12) * art.pointsPerUnit;
            texts.add(new TextBox(item, text, ink, points));
            if (points + .015 < minimumPoints)
                audit.add(false, "font_size", item.panel(), text + " · " + round(points) + " pt");
            if (outside(page, ink, tolerance) || item.clip() != null && outside(item.clip(), ink, tolerance))
                audit.add(true, "text_clip", item.panel(), text);
        }
        for (int i = 0; i < texts.size(); i++) {
            var a = texts.get(i);
            for (int j = i + 1; j < texts.size(); j++) {
                var b = texts.get(j);
                var overlap = a.ink.createIntersection(b.ink);
                if (overlap.getWidth() > tolerance && overlap.getHeight() > tolerance)
                    audit.add(false, "text_overlap", a.item.panel(), a.value + " / " + b.value);
            }
        }
        audit.lines(art, texts, tolerance);
        audit.panelAlignment(art, tolerance);
        var missing = figure.metrics().get("unplaced_priority_labels");
        if (missing instanceof Number count && count.intValue() > 0)
            audit.add(false, "priority_labels", "", "未排入的重点标签 " + count);
        return new Report(audit.errors > 0 ? "FAIL" : audit.warnings > 0 ? "WARN" : "PASS",
            audit.warnings, audit.errors, List.copyOf(audit.issues));
    }

    private void lines(PaperArtwork art, List<TextBox> texts, double tolerance) throws IOException {
        Set<TextBox> flagged = new HashSet<>();
        var labels = texts.stream().filter(text -> !text.item.attr("data-label-for").isBlank()
            && !PaperArtwork.bold(text.item)).toList();
        if (labels.isEmpty()) return;
        for (var edge : art.items) {
            if (!edge.name().equals("path") || !edge.attr("data-focus").equals("true")
                || edge.number("opacity", 1) < .55) continue;
            var shape = edge.shape();
            var bounds = shape.getBounds2D();
            for (var label : labels) {
                if (flagged.contains(label) || !label.item.panel().equals(edge.panel())) continue;
                var inner = new Rectangle2D.Double(label.ink.getX() + tolerance, label.ink.getY() + tolerance,
                    label.ink.getWidth() - 2 * tolerance, label.ink.getHeight() - 2 * tolerance);
                if (inner.isEmpty() || !bounds.intersects(inner) && !bounds.isEmpty()) continue;
                if (crosses(shape.getPathIterator(null, tolerance), inner)) {
                    flagged.add(label);
                    add(false, "label_edge", edge.panel(), label.value + " · 重点连线穿过标签区域");
                }
            }
        }
    }

    private static boolean crosses(PathIterator path, Rectangle2D box) {
        float[] coordinate = new float[6];
        float x = 0, y = 0;
        while (!path.isDone()) {
            int kind = path.currentSegment(coordinate);
            if (kind == PathIterator.SEG_LINETO && box.intersectsLine(x, y, coordinate[0], coordinate[1])) return true;
            x = coordinate[0];
            y = coordinate[1];
            path.next();
        }
        return false;
    }

    private void panelAlignment(PaperArtwork art, double tolerance) {
        var groups = art.root.getElementsByTagNameNS(art.root.getNamespaceURI(), "g");
        var panels = new ArrayList<double[]>();
        for (int i = 0; i < groups.getLength(); i++) {
            var panel = (org.w3c.dom.Element) groups.item(i);
            if (panel.hasAttribute("data-panel-box")) panels.add(PaperArtwork.values(panel.getAttribute("data-panel-box")));
        }
        if (panels.size() < 2) return;
        var reference = panels.get(0);
        var page = new Rectangle2D.Double(0, 0, art.width, art.height);
        for (int i = 0; i < panels.size(); i++) {
            var box = panels.get(i);
            String letter = Character.toString('a' + i);
            if (Math.abs(box[2] - reference[2]) > tolerance || Math.abs(box[3] - reference[3]) > tolerance)
                add(true, "panel_alignment", letter, "子图的宽或高与其他子图不同");
            var area = new Rectangle2D.Double(box[0], box[1], box[2], box[3]);
            if (outside(page, area, tolerance)) add(true, "panel_clip", letter, "子图越出页面");
            for (int j = 0; j < i; j++) {
                var other = panels.get(j);
                if (area.intersects(other[0], other[1], other[2], other[3]))
                    add(true, "panel_overlap", letter, "子图与 " + Character.toString('a' + j) + " 重叠");
            }
        }
    }

    private static boolean outside(Rectangle2D container, Rectangle2D content, double slack) {
        return content.getMinX() < container.getMinX() - slack || content.getMaxX() > container.getMaxX() + slack
            || content.getMinY() < container.getMinY() - slack || content.getMaxY() > container.getMaxY() + slack;
    }

    private void add(boolean fail, String code, String panel, String text) {
        if (fail) errors++; else warnings++;
        if (issues.size() < DETAIL_LIMIT) issues.add(new Issue(fail ? "FAIL" : "WARN", code, panel, text));
    }

    private static double round(double value) { return Math.round(value * 100) / 100d; }
}
