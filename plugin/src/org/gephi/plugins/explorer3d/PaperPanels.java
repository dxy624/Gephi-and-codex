package org.gephi.plugins.explorer3d;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record PaperPanels(String layout, List<Panel> items) {
    static final PaperPanels NONE = new PaperPanels("grid", List.of());

    enum Type { NETWORK, COUNTS, IMAGE }

    record Panel(Type type, int year, String title, String path) {
        Panel {
            if (title.length() > 100) throw new IllegalArgumentException("子图标题最多 100 字符");
            if (year < 0 || year > 9999 || type != Type.NETWORK && year != 0)
                throw new IllegalArgumentException("年份只能用于网络子图");
            if (type == Type.IMAGE && path.isBlank()) throw new IllegalArgumentException("请为示意图选择 PNG 文件");
            if (type != Type.IMAGE && !path.isBlank()) throw new IllegalArgumentException("文件路径只能用于示意图子图");
        }

        static Panel network(int year) { return new Panel(Type.NETWORK, year, "", ""); }
        static Panel counts() { return new Panel(Type.COUNTS, 0, "", ""); }
        String kind() { return type.name().toLowerCase(Locale.ROOT); }
    }

    PaperPanels {
        items = List.copyOf(items);
        if (!Set.of("grid", "row").contains(layout)) throw new IllegalArgumentException("组图排版必须为 grid 或 row");
        if (!items.isEmpty() && (items.size() < 2 || items.size() > 4
            || items.stream().noneMatch(panel -> panel.type == Type.NETWORK)))
            throw new IllegalArgumentException("请选择 2–4 幅子图，其中至少一幅网络图");
    }

    boolean enabled() { return !items.isEmpty(); }
    int columns() { return layout.equals("row") ? items.size() : Math.min(2, items.size()); }

    static PaperPanels read(JsonObject input) {
        if (!input.has("panels") || input.get("panels").isJsonNull()) return NONE;
        var entries = input.getAsJsonArray("panels");
        if (entries.size() < 2 || entries.size() > 4)
            throw new IllegalArgumentException("请选择 2–4 幅子图");
        var items = new ArrayList<Panel>();
        for (var entry : entries) {
            var cell = entry.getAsJsonObject();
            Type type;
            try {
                type = Type.valueOf(Engine.str(cell, "type", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("子图类型必须为 network、counts 或 image");
            }
            items.add(new Panel(type, PaperOptions.year(cell, "year"), Engine.str(cell, "title", "").strip(),
                Engine.str(cell, "path", "").strip()));
        }
        return new PaperPanels(Engine.str(input, "panel_layout", "grid"), items);
    }
}
