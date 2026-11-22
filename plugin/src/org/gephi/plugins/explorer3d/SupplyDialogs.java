package org.gephi.plugins.explorer3d;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;

final class SupplyDialogs {
    private static final List<String> DIRECTIONS = List.of("upstream", "downstream", "both");
    private static final Map<String,String> FILTERS = Map.of("community", "社区", "layers", "隐藏层",
        "degree", "最小度数", "enterprise", "企业代码或名称", "city", "城市", "isolation", "高亮隔离");

    private SupplyDialogs() { }

    static void scope(ExplorerPanel panel) {
        Scene scene = panel.engine.requireScene();
        int workspace = scene.workspaceId;
        long revision = scene.revision;
        var directions = new JComboBox<>(DIRECTIONS.stream().map(SupplyScope::directionLabel).toArray(String[]::new));
        int direction = DIRECTIONS.indexOf(scene.neighborhoodDirection);
        directions.setSelectedIndex(direction < 0 ? 2 : direction);
        var hops = new JComboBox<>(new String[]{"一阶（直接关系）", "两阶"});
        hops.setSelectedIndex(scene.neighborhoodHops == 2 ? 1 : 0);
        var content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel(scene.selection.isEmpty() ? "请先用查找或鼠标选择企业。"
            : "当前选中 " + scene.selection.size() + " 家企业"), BorderLayout.NORTH);
        var form = new JPanel(new GridLayout(0, 2, 8, 6));
        row(form, "关系方向", directions);
        row(form, "关系范围", hops);
        content.add(form, BorderLayout.CENTER);
        if (scene.directedScope()) content.add(new JLabel(summary(scene)), BorderLayout.SOUTH);
        String[] actions = scene.directedScope()
            ? new String[]{"查看", "解除一项限制…", "退出上下游视图", "取消"} : new String[]{"查看", "取消"};
        int action = JOptionPane.showOptionDialog(panel, content, "查看上下游", JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
        if (action == 0) panel.actIn(workspace, revision, "supply_scope", "direction",
            DIRECTIONS.get(directions.getSelectedIndex()), "hops", hops.getSelectedIndex() + 1,
            "width", panel.paperViewWidth(), "height", panel.paperViewHeight());
        else if (scene.directedScope() && action == 1) unblock(panel, scene, revision);
        else if (scene.directedScope() && action == 2) panel.actIn(workspace, revision, "clear_supply_scope");
    }

    private static void unblock(ExplorerPanel panel, Scene scene, long revision) {
        var blocked = blocked(scene.supplyScope().report(scene));
        if (blocked.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "当前没有企业被社区、层开关或其他节点条件挡住。", "上下游筛选",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        var criteria = new ArrayList<>(blocked.keySet());
        var labels = criteria.stream().map(key -> FILTERS.get(key) + "（挡住 " + blocked.get(key) + " 家）").toArray(String[]::new);
        Object selected = JOptionPane.showInputDialog(panel, "选择要解除的限制", "上下游筛选", JOptionPane.PLAIN_MESSAGE,
            null, labels, labels[0]);
        if (selected != null) panel.actIn(scene.workspaceId, revision, "clear_scope_block",
            "criterion", criteria.get(List.of(labels).indexOf(selected)), "width", panel.paperViewWidth(), "height", panel.paperViewHeight());
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Integer> blocked(Map<String,Object> report) {
        return (Map<String,Integer>) report.get("blocked");
    }

    static String summary(Scene scene) {
        var report = scene.supplyScope().report(scene);
        String line = scene.neighborhoodHops + " 阶" + SupplyScope.directionLabel(scene.neighborhoodDirection)
            + " · " + report.get("related_firms") + " 家";
        if (Boolean.TRUE.equals(report.get("map_available"))) {
            line += " · " + report.get("mapped_firms") + " 家可定位、" + report.get("unmapped_firms") + " 家缺少定位";
        }
        var blocked = blocked(report);
        if (!blocked.isEmpty()) {
            line += " · 当前限制：" + String.join("、", blocked.keySet().stream().map(FILTERS::get).toList());
        }
        return line;
    }

    static String yearSummary(Scene scene) {
        if (scene.referenceYear == 0) return scene.activeYear + " 年关系";
        var counts = EdgeYears.comparison(scene);
        return "年份对比 " + scene.referenceYear + " → " + scene.activeYear + "\n"
            + "新增记录 " + counts.get("added") + " 条 · 消失记录 " + counts.get("removed") + " 条";
    }

    static void presets(ExplorerPanel panel) {
        Scene scene = panel.engine.requireScene();
        long revision = scene.revision;
        var names = new ArrayList<>(List.of(FigurePreset.CHAIN, FigurePreset.MAP));
        names.addAll(scene.figurePresets.keySet());
        var choices = new JComboBox<>(names.stream().map(n -> "预设 · " + n).toArray(String[]::new));
        var camera = new JCheckBox("同时载入已保存的视角");
        var form = new JPanel(new GridLayout(0, 1, 0, 6));
        form.add(choices);
        form.add(camera);
        String[] actions = {"应用预设", "保存当前设置…", "取消"};
        int action = JOptionPane.showOptionDialog(panel, form, "出图预设", JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
        if (action == 0) panel.actIn(scene.workspaceId, revision, "figure_preset", "action", "apply",
            "name", names.get(choices.getSelectedIndex()), "restore_camera", camera.isSelected(),
            "width", panel.paperViewWidth(), "height", panel.paperViewHeight());
        if (action == 1) {
            String name = JOptionPane.showInputDialog(panel, "为当前外观、图例和视角命名", "保存出图预设",
                JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return;
            if (scene.figurePresets.containsKey(name.strip()) && JOptionPane.showConfirmDialog(panel, "覆盖同名预设？",
                "保存出图预设", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            panel.actIn(scene.workspaceId, revision, "figure_preset", "action", "save", "name", name);
        }
    }

    static void years(ExplorerPanel panel) {
        Scene scene = panel.engine.requireScene();
        long revision = scene.revision;
        var available = new ArrayList<>(EdgeYears.available(scene));
        if (available.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "当前工程没有逐年的边记录。请导入包含 year 列的关系表，或带边年份的 GEXF。",
                "年份与对比", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        available.add(0, 0);
        var active = new JComboBox<>(available.stream().map(y -> y == 0 ? "全部年份" : y.toString()).toArray(String[]::new));
        var reference = new JComboBox<>(available.stream().map(y -> y == 0 ? "不对比" : y.toString()).toArray(String[]::new));
        active.setSelectedIndex(available.indexOf(scene.activeYear));
        reference.setSelectedIndex(available.indexOf(scene.referenceYear));
        reference.setEnabled(scene.activeYear != 0);
        active.addActionListener(event -> {
            reference.setEnabled(active.getSelectedIndex() != 0);
            if (active.getSelectedIndex() == 0) reference.setSelectedIndex(0);
        });
        var form = new JPanel(new GridLayout(0, 2, 8, 6));
        row(form, "显示年份", active);
        row(form, "对比基准", reference);
        var content = new JPanel(new BorderLayout(0, 8));
        content.add(form, BorderLayout.CENTER);
        content.add(new JLabel("绿色为仅显示年份有记录，红色为仅基准年份有记录。"), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(panel, content, "年份与对比", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) panel.actIn(scene.workspaceId, revision, "years",
            "year", available.get(active.getSelectedIndex()), "reference", available.get(reference.getSelectedIndex()),
            "width", panel.paperViewWidth(), "height", panel.paperViewHeight());
    }

    private static void row(JPanel panel, String label, JComponent input) {
        var text = new JLabel(label);
        text.setLabelFor(input);
        panel.add(text);
        panel.add(input);
    }
}
