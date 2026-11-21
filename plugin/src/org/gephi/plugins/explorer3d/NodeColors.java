package org.gephi.plugins.explorer3d;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** A workspace-wide scale shared by the interactive renderer and SVG export. */
record NodeColors(String field, Map<String, Integer> categories, double min, double max, boolean numeric) {
    static final int MISSING = 0x86939d;
    static final int LOW = 0x86b9d7;
    static final int HIGH = 0x164671;

    static NodeColors create(Scene scene) {
        boolean numeric = scene.colorMode.equals("number");
        String field = scene.colorField;
        if (field.isEmpty()) return new NodeColors(field, Map.of(), 0, 0, false);
        var groups = new TreeSet<String>();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Scene.Vertex node : scene.nodes) {
            String value = Scene.fieldValue(node, field);
            if (numeric) {
                double number = number(value);
                if (Double.isFinite(number)) {
                    min = Math.min(min, number);
                    max = Math.max(max, number);
                }
            } else if (!value.isBlank()) groups.add(value);
        }
        var palette = new LinkedHashMap<String, Integer>();
        for (String group : groups) palette.put(group, DiagramRenderer.paletteColor(palette.size()));
        return new NodeColors(field, Collections.unmodifiableMap(palette), min, max, numeric);
    }

    boolean active() { return !field.isEmpty(); }

    int color(Scene.Vertex node) {
        String value = Scene.fieldValue(node, field);
        if (!numeric) return categories.getOrDefault(value, MISSING);
        double number = number(value);
        if (!Double.isFinite(number)) return MISSING;
        double range = max - min;
        double fraction = min == max ? .5 : Double.isFinite(range) ? (number - min) / range
            : (number / 2 - min / 2) / (max / 2 - min / 2);
        return DiagramRenderer.blend(LOW, HIGH, fraction);
    }

    boolean missing(Scene.Vertex node) {
        String value = Scene.fieldValue(node, field);
        return numeric ? !Double.isFinite(number(value)) : value.isBlank();
    }

    static double number(String value) {
        if (value.isBlank()) return Double.NaN;
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ex) { return Double.NaN; }
    }
}
