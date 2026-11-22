package org.gephi.plugins.explorer3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Prepared map geometry and firm positions in the same projected coordinates. */
final class GeoLayer {
    String source;
    String direction;
    String locationReference;
    List<double[][][]> polygons;
    List<double[][]> lines;
    List<City> cities;
    Map<String, double[]> positions;
    List<String> leaders;
    List<String> communities;
    double[] bounds;

    record City(String code, String name, double x, double y, int count) { }

    static GeoLayer read(String value, Scene scene) {
        Path file = Path.of(value);
        if (!file.isAbsolute() || !value.endsWith(".json")) {
            throw new IllegalArgumentException("Prepared geography needs a local absolute .json path");
        }
        try {
            if (Files.size(file) > 10_000_000) throw new IllegalArgumentException("Prepared geography exceeds 10 MB");
            GeoLayer layer;
            try (var reader = Files.newBufferedReader(file)) {
                layer = Scene.JSON.fromJson(reader, GeoLayer.class);
            }
            layer.validate(scene);
            return layer;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read prepared geography: " + exception.getMessage(), exception);
        }
    }

    private void validate(Scene scene) {
        if (source == null || source.isBlank() || source.length() > 180 || positions == null || positions.isEmpty()
            || cities == null || cities.isEmpty() || leaders == null || communities == null
            || polygons == null || polygons.isEmpty() || lines == null || !coordinate(bounds, 4)
            || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) {
            throw new IllegalArgumentException("Incomplete prepared geography");
        }
        int points = 0;
        for (double[][][] polygon : polygons) {
            if (polygon == null || polygon.length == 0) throw new IllegalArgumentException("Empty map polygon");
            for (double[][] ring : polygon) points += validatePath(ring, 4);
        }
        for (double[][] line : lines) points += validatePath(line, 2);
        if (points > 125_000) throw new IllegalArgumentException("Simplify the map to at most 125000 vertices");
        var ids = scene.nodes.stream().map(node -> node.id).collect(java.util.stream.Collectors.toSet());
        for (var position : positions.entrySet()) {
            if (!ids.contains(position.getKey()) || !coordinate(position.getValue(), 3)) {
                throw new IllegalArgumentException("Invalid map position: " + position.getKey());
            }
        }
        if (!ids.containsAll(leaders)) throw new IllegalArgumentException("Unknown chain leader ID");
        for (City city : cities) {
            if (city == null || city.code == null || city.name == null || city.count < 1
                || !Double.isFinite(city.x) || !Double.isFinite(city.y)) {
                throw new IllegalArgumentException("Invalid city marker");
            }
        }
    }

    private static int validatePath(double[][] path, int minimum) {
        if (path == null || path.length < minimum) throw new IllegalArgumentException("Incomplete map path");
        for (double[] point : path) if (!coordinate(point, 2)) throw new IllegalArgumentException("Invalid map coordinate");
        return path.length;
    }

    private static boolean coordinate(double[] values, int length) {
        if (values == null || values.length != length) return false;
        for (double value : values) if (!Double.isFinite(value) || Math.abs(value) > 100_000) return false;
        return true;
    }
}
