package org.gephi.plugins.explorer3d;

import java.util.Map;
import java.util.Set;

record FigurePreset(String style, boolean dark, boolean labels, boolean arrows, double arrowScale,
                    double edgeWidth, double nodeScale, String field, String mode, boolean geography, double[] camera,
                    String legendPosition, Set<String> hiddenLegendItems, Map<String, String> legendLabels) {
    static final String CHAIN = "链主关系图", MAP = "地理供应链图";

    static FigurePreset capture(Scene scene) {
        return new FigurePreset(scene.visualStyle, scene.dark, scene.labels, scene.showsArrows(), scene.arrowScale,
            scene.edgeWidth, scene.nodeScale, scene.colorField, scene.colorMode, scene.geographyEnabled,
            scene.cameraValues(), scene.legendPosition, Set.copyOf(scene.hiddenLegendItems), Map.copyOf(scene.legendLabels));
    }

    static FigurePreset builtIn(String name) {
        return switch (name) {
            case CHAIN, MAP -> new FigurePreset("communities", false, true, true, 1.2, 1.35, 1, "", "category",
                name.equals(MAP), null, "bottom", Set.of(), Map.of());
            default -> null;
        };
    }

    void apply(Scene scene, boolean restoreCamera) {
        scene.toggleGeography(geography);
        scene.visualStyle = style;
        scene.dark = dark;
        scene.labels = labels;
        scene.arrows = arrows;
        scene.arrowsManuallySet = true;
        scene.arrowScale = arrowScale;
        scene.edgeWidth = edgeWidth;
        scene.nodeScale = nodeScale;
        scene.colorField = field;
        scene.colorMode = mode;
        scene.legendPosition = legendPosition;
        scene.hiddenLegendItems = new java.util.LinkedHashSet<>(hiddenLegendItems);
        scene.legendLabels = new java.util.LinkedHashMap<>(legendLabels);
        if (restoreCamera && camera != null) scene.restoreCamera(camera);
        scene.refreshViewCaches();
    }
}
