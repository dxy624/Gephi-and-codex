package org.gephi.plugins.explorer3d;

import com.google.gson.JsonParser;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.Edge;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

final class EdgeYears {
    static final int ADDED = 0x187544, REMOVED = 0xb43e2b;

    private EdgeYears() { }

    static Map<Integer, Double> read(Edge edge, double weight) {
        Object annual = null, single = null;
        for (Column column : edge.getAttributeColumns()) {
            String id = column.getId(), title = column.getTitle();
            if ("year_weights".equalsIgnoreCase(id) || "year_weights".equalsIgnoreCase(title)) annual = edge.getAttribute(column);
            if ("year".equalsIgnoreCase(id) || "year".equalsIgnoreCase(title)) single = edge.getAttribute(column);
        }
        if (annual == null && single == null) return Map.of();
        try {
            if (annual != null && !annual.toString().isBlank()) {
                var years = new LinkedHashMap<Integer, Double>();
                for (var entry : JsonParser.parseString(annual.toString()).getAsJsonObject().entrySet()) {
                    double value = entry.getValue().getAsDouble();
                    if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid annual weight");
                    if (years.putIfAbsent(parseYear(entry.getKey()), value) != null) throw new IllegalArgumentException("Duplicate year");
                }
                return years;
            }
            if (single == null || single.toString().isBlank()) return Map.of();
            return Map.of(parseYear(single.toString()), weight);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("无效的边年份或年度权重，边 ID " + edge.getId(), ex);
        }
    }

    static int parseYear(String value) {
        double number = Double.parseDouble(value);
        if (number < 1 || number > 9999 || number != (int) number) throw new IllegalArgumentException("Invalid year");
        return (int) number;
    }

    static TreeSet<Integer> available(Scene scene) {
        var years = new TreeSet<Integer>();
        for (Scene.Link edge : scene.edges) years.addAll(edge.years.keySet());
        return years;
    }

    static double weight(Scene scene, Scene.Link edge) {
        if (scene.activeYear == 0) return edge.weight;
        Double current = edge.years.get(scene.activeYear);
        Double reference = scene.referenceYear == 0 ? null : edge.years.get(scene.referenceYear);
        if (current == null) return reference == null ? Double.NaN : reference;
        return reference != null && current < scene.minWeight && reference >= scene.minWeight ? reference : current;
    }

    static String change(Scene scene, Scene.Link edge) {
        if (scene.referenceYear == 0) return "";
        boolean current = edge.years.containsKey(scene.activeYear);
        boolean reference = edge.years.containsKey(scene.referenceYear);
        return current == reference ? "same" : current ? "added" : "removed";
    }

    static int changeColor(Scene scene, Scene.Link edge, int fallback) {
        return switch (change(scene, edge)) {
            case "added" -> ADDED;
            case "removed" -> REMOVED;
            default -> fallback;
        };
    }

    static Map<String, Long> comparison(Scene scene) {
        Map<String, Long> counts = new LinkedHashMap<>(Map.of("added", 0L, "removed", 0L, "same", 0L));
        if (scene.referenceYear != 0) for (Scene.Link edge : scene.edges) {
            if (scene.visible(edge)) counts.merge(change(scene, edge), 1L, Long::sum);
        }
        return counts;
    }
}
