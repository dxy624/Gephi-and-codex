package org.gephi.plugins.explorer3d;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record PaperOptions(double nodeScale, double edgeScale, double arrowScale, Boolean arrows,
                    double fontPoints, double contextOpacity, Map<String, String> marks,
                    boolean automaticCaption, String caption, String weightMeaning,
                    int leftYear, int rightYear) {

    static final PaperOptions DEFAULT = new PaperOptions(1, 1, 1, null, 6.8, -1, Map.of(), false, "", "", 0, 0);

    PaperOptions {
        range(nodeScale, .25, 4, "节点倍率");
        range(edgeScale, .25, 4, "线宽倍率");
        range(arrowScale, .25, 4, "箭头倍率");
        range(fontPoints, 4, 18, "字号");
        if (contextOpacity != -1) range(contextOpacity, 0, 1, "背景线透明度");
        if (marks.size() > 24 || marks.keySet().stream().anyMatch(String::isBlank)
            || marks.values().stream().anyMatch(value -> value == null || value.length() > 120))
            throw new IllegalArgumentException("最多标注 24 个节点，每个名称最多 120 字符");
        if (caption.length() > 1200 || weightMeaning.length() > 160)
            throw new IllegalArgumentException("图注最多 1200 字符，边权含义最多 160 字符");
        if ((leftYear != 0 || rightYear != 0) && (leftYear <= 0 || rightYear <= 0 || leftYear == rightYear))
            throw new IllegalArgumentException("请选择两个不同的年份");
        marks = Collections.unmodifiableMap(new LinkedHashMap<>(marks));
    }

    private static void range(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + "超出范围 [" + min + ", " + max + "]");
    }

    boolean paired() { return leftYear != 0; }

    static PaperOptions read(JsonObject source) {
        if (source == null) return DEFAULT;
        var marks = new LinkedHashMap<String, String>();
        if (source.has("marks")) source.getAsJsonObject("marks").entrySet()
            .forEach(entry -> marks.put(entry.getKey().strip(), entry.getValue().getAsString().strip()));
        return new PaperOptions(Engine.num(source, "node_scale", 1), Engine.num(source, "edge_scale", 1),
            Engine.num(source, "arrow_scale", 1), source.has("arrows") ? Engine.bool(source, "arrows", false) : null,
            Engine.num(source, "font_points", 6.8), Engine.num(source, "context_opacity", -1), marks,
            Engine.bool(source, "automatic_caption", DEFAULT.automaticCaption()), Engine.str(source, "caption", "").strip(),
            Engine.str(source, "weight_meaning", "").strip(),
            year(source, "left_year"), year(source, "right_year"));
    }

    private static int year(JsonObject source, String key) {
        double value = Engine.num(source, key, 0);
        if (value != Math.rint(value) || value < 0 || value > 9999)
            throw new IllegalArgumentException("年份必须是 1–9999 的整数");
        return (int) value;
    }
}
