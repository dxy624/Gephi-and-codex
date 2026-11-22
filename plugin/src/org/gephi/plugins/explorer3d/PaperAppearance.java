package org.gephi.plugins.explorer3d;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

record PaperAppearance(String preset, String font, Double marginMm, Integer keyNodeColor, Integer focusEdgeColor) {
    static final Map<String, String> PRESETS = Map.of("current", "沿用当前", "journal", "论文浅色", "grayscale", "黑白打印");
    private static final Map<String, String> FAMILIES = Map.of(
        "yahei", "Microsoft YaHei", "simsun", "SimSun", "arial", "Arial", "times", "Times New Roman");
    static final PaperAppearance DEFAULT = new PaperAppearance("current", "yahei", null, null, null);

    PaperAppearance {
        if (!PRESETS.containsKey(preset)) throw new IllegalArgumentException("未知论文配色 " + preset);
        if (!FAMILIES.containsKey(font)) throw new IllegalArgumentException("未知论文字体 " + font);
        if (marginMm != null && (!Double.isFinite(marginMm) || marginMm < 2 || marginMm > 16))
            throw new IllegalArgumentException("论文页边距须在 2–16 毫米之间");
    }

    static PaperAppearance read(JsonObject input) {
        if (input == null) return DEFAULT;
        Double margin = input.has("margin_mm") && !input.get("margin_mm").isJsonNull()
            ? Engine.num(input, "margin_mm", 0) : null;
        return new PaperAppearance(Engine.str(input, "preset", DEFAULT.preset), Engine.str(input, "font", DEFAULT.font),
            margin, readColor(input, "key_node_color"), readColor(input, "focus_edge_color"));
    }

    private static Integer readColor(JsonObject input, String key) {
        String value = Engine.str(input, key, "").strip();
        if (value.isEmpty()) return null;
        if (!value.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException(key + " 须使用 #RRGGBB 格式");
        return Integer.parseInt(value.substring(1), 16);
    }

    boolean grayscale() { return preset.equals("grayscale"); }
    boolean followsView() { return preset.equals("current"); }
    String family() { return FAMILIES.get(font); }
    String cssFonts() {
        return switch (font) {
            case "yahei" -> "Microsoft YaHei, sans-serif";
            case "arial" -> "Arial, Microsoft YaHei, sans-serif";
            case "times" -> "Times New Roman, SimSun, Microsoft YaHei, serif";
            case "simsun" -> "SimSun, Microsoft YaHei, serif";
            default -> throw new IllegalStateException("Unknown paper font");
        };
    }

    int nodeInk() {
        if (grayscale()) return 0x252525;
        return keyNodeColor != null ? keyNodeColor : followsView() ? DiagramRenderer.ACCENT : 0x17647b;
    }
    int focusInk() {
        if (grayscale()) return 0x363636;
        return focusEdgeColor != null ? focusEdgeColor : followsView() ? 0xa34121 : 0x8f412a;
    }
    int contextInk() { return followsView() ? 0x687c8a : 0x7c8791; }

    int display(int rgb) {
        if (!grayscale()) return rgb;
        int level = (int) Math.round(.2126 * ((rgb >>> 16) & 255) + .7152 * ((rgb >>> 8) & 255) + .0722 * (rgb & 255));
        return level * 0x010101;
    }

    static String hex(int rgb) {
        return "#" + Integer.toHexString(0x1000000 | (rgb & 0xffffff)).substring(1);
    }

    Map<String, Object> metadata() {
        var result = new LinkedHashMap<String, Object>();
        result.put("preset", preset);
        result.put("font", font);
        if (marginMm != null) result.put("margin_mm", marginMm);
        if (keyNodeColor != null) result.put("key_node_color", hex(keyNodeColor));
        if (focusEdgeColor != null) result.put("focus_edge_color", hex(focusEdgeColor));
        return result;
    }
}
