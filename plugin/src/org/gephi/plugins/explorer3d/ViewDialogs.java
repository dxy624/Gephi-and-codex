package org.gephi.plugins.explorer3d;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

final class ViewDialogs {
    private ViewDialogs() { }

    static void filter(ExplorerPanel panel) {
        Scene scene = panel.engine.requireScene();
        long revision = scene.revision;
        var node = new JTextField(scene.nodeQuery, 18);
        var city = new JTextField(scene.cityQuery, 18);
        var community = new JComboBox<String>();
        community.setEditable(true);
        var renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        community.setRenderer(renderer);
        community.addItem("");
        scene.nodes.stream().map(v -> v.community).filter(v -> !v.isBlank()).distinct().sorted()
            .forEach(community::addItem);
        community.setSelectedItem(scene.communityFilter);
        var degree = new JSpinner(new SpinnerNumberModel(scene.minDegree, 0, Integer.MAX_VALUE, 1));
        var weight = new JTextField(scene.minWeight <= -1e100 ? "" : Double.toString(scene.minWeight), 18);
        var neighborhood = new JComboBox<>(new String[]{"不限", "只看一阶邻居", "只看两阶邻居"});
        neighborhood.setSelectedIndex(scene.neighborhoodHops);
        var selected = new JCheckBox("使用当前选中的节点", scene.neighborhoodHops == 0);
        var form = new JPanel(new GridLayout(0, 2, 8, 7));
        row(form, "企业代码或名称", node);
        row(form, "城市名称或代码", city);
        row(form, "社区（留空表示不限）", community);
        row(form, "最小度数", degree);
        row(form, "最小边权（留空表示不限）", weight);
        row(form, "邻居范围", neighborhood);
        row(form, "邻居中心", selected);
        long count = scene.nodes.stream().filter(scene::visible).count();
        long edges = scene.edges.stream().filter(scene::visible).count();
        var content = new JPanel(new BorderLayout(0, 10));
        content.add(new JLabel("当前显示 " + count + " 个节点 / " + edges + " 条边"), BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        String[] options = {"应用", "清除筛选", "取消"};
        int action = JOptionPane.showOptionDialog(panel, content, "筛选网络", JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (action == 1) {
            panel.actIn(scene.workspaceId, revision, "filter");
        } else if (action == 0) {
            double minWeight = weight.getText().isBlank() ? -Double.MAX_VALUE : Double.parseDouble(weight.getText().strip());
            var roots = selected.isSelected() || scene.neighborhoodRoots.isEmpty() ? scene.selection : scene.neighborhoodRoots;
            panel.actIn(scene.workspaceId, revision, "filter", "node_query", node.getText(), "city_query", city.getText(),
                "community", community.getEditor().getItem().toString().strip(), "min_degree", degree.getValue(),
                "min_weight", minWeight, "hops", neighborhood.getSelectedIndex(), "roots", roots,
                "direction", scene.neighborhoodDirection, "width", panel.paperViewWidth(), "height", panel.paperViewHeight());
        }
    }

    static void layers(ExplorerPanel panel) {
        Scene scene = panel.engine.requireScene();
        long revision = scene.revision;
        if (scene.layerAttribute.isEmpty()) {
            chooseLayerField(panel, scene, revision);
            return;
        }
        var layers = new ArrayList<>(scene.layers());
        Object[][] rows = new Object[layers.size()][2];
        for (int i = 0; i < layers.size(); i++) {
            String layer = layers.get(i);
            rows[i][0] = !scene.hiddenLayers.contains(layer);
            rows[i][1] = layer.isBlank() ? "未填写" : layer;
        }
        var model = tableModel(rows, new String[]{"显示", "层"}, false);
        var table = new JTable(model);
        plainText(table);
        table.getColumnModel().getColumn(0).setMaxWidth(65);
        var content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel("分层字段 · " + fieldName(scene.layerAttribute)), BorderLayout.NORTH);
        content.add(scroll(table), BorderLayout.CENTER);
        String[] options = {"应用", "显示全部层", "更换分层字段", "取消"};
        int action = JOptionPane.showOptionDialog(panel, content, "层开关", JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (action == 1) panel.actIn(scene.workspaceId, revision, "layer_visibility", "hidden", List.of());
        if (action == 2) chooseLayerField(panel, scene, revision);
        if (action == 0) {
            stopEditing(table);
            var hidden = new ArrayList<String>();
            for (int i = 0; i < layers.size(); i++) if (!Boolean.TRUE.equals(model.getValueAt(i, 0))) hidden.add(layers.get(i));
            panel.actIn(scene.workspaceId, revision, "layer_visibility", "hidden", hidden);
        }
    }

    private static void chooseLayerField(ExplorerPanel panel, Scene scene, long revision) {
        var fields = new ArrayList<>(scene.fields());
        var choices = new JComboBox<>(fields.stream().map(ViewDialogs::fieldName).toArray(String[]::new));
        int current = fields.indexOf(scene.layerAttribute);
        choices.setSelectedIndex(current < 0 ? fields.indexOf("community") : current);
        var form = new JPanel(new GridLayout(0, 2, 8, 7));
        row(form, "分层字段", choices);
        if (JOptionPane.showConfirmDialog(panel, form, "按字段分层", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            panel.actIn(scene.workspaceId, revision, "layer", "attribute", fields.get(choices.getSelectedIndex()),
                "spacing", scene.layerSpacing);
            layers(panel);
        }
    }

    static void colors(ExplorerPanel panel) {
        Scene scene = panel.engine.requireScene();
        long revision = scene.revision;
        var fields = new ArrayList<>(scene.fields());
        fields.add(0, "");
        var choices = new JComboBox<>(fields.stream().map(ViewDialogs::fieldName).toArray(String[]::new));
        choices.setSelectedIndex(Math.max(0, fields.indexOf(scene.colorField)));
        var mode = new JComboBox<>(new String[]{"类别配色", "数值渐变"});
        mode.setSelectedIndex(scene.colorMode.equals("number") ? 1 : 0);
        var form = new JPanel(new GridLayout(0, 2, 8, 7));
        row(form, "节点字段", choices);
        row(form, "配色方式", mode);
        if (JOptionPane.showConfirmDialog(panel, form, "按字段配色", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            panel.actIn(scene.workspaceId, revision, "node_colors", "field", fields.get(choices.getSelectedIndex()),
                "mode", mode.getSelectedIndex() == 1 ? "number" : "category");
        }
    }

    static void legend(ExplorerPanel panel, Component parent, double widthMm, String direction) {
        Scene scene = panel.engine.requireScene();
        int workspace = scene.workspaceId;
        long revision = scene.revision;
        List<PaperSvg.Key> keys = PaperSvg.legendItems(panel.engine.paperSnapshot(workspace),
            panel.paperViewWidth(), panel.paperViewHeight(), widthMm, direction);
        Scene draft = scene.copy();
        if (editLegend(parent, draft, keys))
            panel.actIn(workspace, revision, "paper_legend", "position", draft.legendPosition,
                "hidden", draft.hiddenLegendItems, "labels", draft.legendLabels);
    }

    static boolean editLegend(Component parent, Scene scene, List<PaperSvg.Key> keys) {
        Object[][] rows = new Object[keys.size()][3];
        for (int i = 0; i < keys.size(); i++) {
            PaperSvg.Key key = keys.get(i);
            rows[i][0] = !scene.hiddenLegendItems.contains(key.id());
            rows[i][1] = key;
            rows[i][2] = scene.legendLabels.getOrDefault(key.id(), key.text());
        }
        var model = tableModel(rows, new String[]{"显示", "符号", "图例名称"}, true);
        var table = new JTable(model);
        plainText(table);
        table.getColumnModel().getColumn(0).setMaxWidth(62);
        table.getColumnModel().getColumn(1).setMaxWidth(66);
        table.getColumnModel().getColumn(1).setCellRenderer((owner, value, selected, focus, row, column) ->
            new LegendSymbol((PaperSvg.Key) value, selected ? owner.getSelectionBackground() : owner.getBackground()));
        var placement = new JComboBox<>(new String[]{"图形下方", "图形上方"});
        placement.setSelectedIndex(scene.legendPosition.equals("top") ? 1 : 0);
        var controls = new JPanel(new BorderLayout(8, 0));
        controls.add(new JLabel("图例位置"), BorderLayout.WEST);
        controls.add(placement, BorderLayout.CENTER);
        var content = new JPanel(new BorderLayout(0, 8));
        content.add(controls, BorderLayout.NORTH);
        content.add(keys.isEmpty() ? new JLabel("当前画面没有可显示的图例项") : scroll(table), BorderLayout.CENTER);
        var note = new JLabel("表格预览当前符号。双击名称可以修改，留空恢复原名。");
        content.add(note, BorderLayout.SOUTH);
        String[] actions = {"保存图例设置", "恢复默认", "取消"};
        int action = JOptionPane.showOptionDialog(parent, content, "论文图例", JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
        if (action == 1) {
            scene.legendPosition = "bottom";
            scene.hiddenLegendItems.clear();
            scene.legendLabels.clear();
        }
        if (action == 0) {
            stopEditing(table);
            var hidden = new LinkedHashSet<>(scene.hiddenLegendItems);
            var labels = new LinkedHashMap<>(scene.legendLabels);
            for (int i = 0; i < keys.size(); i++) {
                var key = keys.get(i);
                String label = model.getValueAt(i, 2).toString().strip();
                if (Boolean.TRUE.equals(model.getValueAt(i, 0))) hidden.remove(key.id());
                else hidden.add(key.id());
                if (label.isEmpty() || label.equals(key.text())) labels.remove(key.id());
                else labels.put(key.id(), label);
            }
            scene.legendPosition = placement.getSelectedIndex() == 1 ? "top" : "bottom";
            scene.hiddenLegendItems = hidden;
            scene.legendLabels = labels;
        }
        return action == 0 || action == 1;
    }

    private static String fieldName(String field) {
        return switch (field) {
            case "" -> "使用外观预设";
            case "community" -> "社区（community）";
            case "degree" -> "连接数（degree）";
            default -> "属性 · " + field;
        };
    }

    private static void row(JPanel panel, String name, JComponent input) {
        var label = new JLabel(name);
        label.setLabelFor(input);
        panel.add(label);
        panel.add(input);
    }

    private static void plainText(JTable table) {
        var renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(String.class, renderer);
    }

    private static DefaultTableModel tableModel(Object[][] rows, String[] headings, boolean editLabel) {
        return new DefaultTableModel(rows, headings) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0 || editLabel && column == 2; }
        };
    }

    private static JScrollPane scroll(JTable table) {
        JScrollPane pane = new JScrollPane(table);
        pane.setPreferredSize(new Dimension(500, Math.min(315, 38 + Math.max(2, table.getRowCount()) * 20)));
        return pane;
    }

    private static void stopEditing(JTable table) {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
    }

    private static final class LegendSymbol extends JComponent {
        private final PaperSvg.Key key;
        private final Color background;

        LegendSymbol(PaperSvg.Key key, Color background) { this.key = key; this.background = background; }

        @Override protected void paintComponent(Graphics graphics) {
            int x = getWidth() / 2, y = getHeight() / 2;
            graphics.setColor(background);
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setColor(new Color(key.color()));
            if (!key.line()) graphics.fillOval(x - 4, y - 4, 8, 8);
            else {
                int stroke = Math.max(1, Math.min(6, Math.round(key.size())));
                graphics.fillRect(x - 13, y - stroke / 2, 25, stroke);
                if (key.arrow()) graphics.fillPolygon(new int[]{x + 13, x + 7, x + 7}, new int[]{y, y - 4, y + 4}, 3);
            }
        }
    }
}
