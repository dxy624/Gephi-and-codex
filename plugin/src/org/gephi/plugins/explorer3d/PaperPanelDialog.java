package org.gephi.plugins.explorer3d;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

final class PaperPanelDialog {
    private static final String[] KIND_LABELS = {"不使用", "网络图", "关系数量统计", "PNG 示意图"};

    private PaperPanelDialog() { }

    static PaperPanels defaults(Integer[] years) {
        if (years.length >= 2) return new PaperPanels("grid", List.of(
            PaperPanels.Panel.network(years[years.length - 2]), PaperPanels.Panel.network(years[years.length - 1]),
            PaperPanels.Panel.counts()));
        return new PaperPanels("grid", List.of(PaperPanels.Panel.network(0), PaperPanels.Panel.counts()));
    }

    static PaperPanels edit(Component parent, PaperPanels previous, Integer[] years) {
        var layout = new JComboBox<>(new String[]{"两列排版", "单行横排"});
        layout.setSelectedIndex(previous.layout().equals("row") ? 1 : 0);
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        toolbar.add(new JLabel("排版"));
        toolbar.add(layout);

        var table = new JPanel(new GridBagLayout());
        row(table, 0, new JLabel("顺序"), new JLabel("类型"), new JLabel("年份或文件"), new JLabel("子图标题（可选）"));
        var slots = new ArrayList<Slot>();
        for (int i = 0; i < 4; i++) {
            var slot = new Slot(i < previous.items().size() ? previous.items().get(i) : null, years);
            slots.add(slot);
            row(table, i + 1, new JLabel(Integer.toString(i + 1)), slot.kind, slot.source, slot.title);
        }
        var content = new JPanel(new BorderLayout(0, 9));
        content.add(toolbar, BorderLayout.NORTH);
        content.add(table, BorderLayout.CENTER);
        content.add(new JLabel("关系数量按所选网络年份统计，并沿用当前筛选。"), BorderLayout.SOUTH);
        while (JOptionPane.showConfirmDialog(parent, content, "编排子图", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                var specs = slots.stream().filter(slot -> slot.kind.getSelectedIndex() > 0).map(Slot::panel).toList();
                if (specs.isEmpty()) throw new IllegalArgumentException("请选择 2–4 幅子图");
                return new PaperPanels(layout.getSelectedIndex() == 1 ? "row" : "grid", specs);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(parent, ex.getMessage(), "子图设置", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    private static void row(JPanel table, int row, JComponent... components) {
        for (int i = 0; i < components.length; i++) {
            var constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.gridx = i;
            constraints.insets = new Insets(3, 4, 3, 4);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.weightx = i == 2 || i == 3 ? 1 : 0;
            table.add(components[i], constraints);
        }
    }

    private static final class Slot {
        private final JComboBox<String> kind = new JComboBox<>(KIND_LABELS);
        private final JComboBox<String> years = new JComboBox<>();
        private final JPanel source = new JPanel(new java.awt.CardLayout());
        private final JButton choose = new JButton("选择 PNG…");
        private final JTextField title = new JTextField(15);
        private final Integer[] availableYears;
        private String path = "";

        Slot(PaperPanels.Panel existing, Integer[] availableYears) {
            this.availableYears = availableYears;
            years.addItem("当前视图");
            for (int year : availableYears) years.addItem(year + " 年");
            source.add(new JLabel(" "), "blank");
            source.add(years, "year");
            source.add(choose, "image");
            if (existing != null) {
                kind.setSelectedIndex(existing.type().ordinal() + 1);
                title.setText(existing.title());
                path = existing.path();
                if (existing.year() > 0) years.setSelectedItem(existing.year() + " 年");
            }
            choose.addActionListener(event -> {
                var file = new JFileChooser();
                file.setDialogTitle("选择用于组图的 PNG");
                file.setFileFilter(new FileNameExtensionFilter("PNG 图片", "png"));
                if (file.showOpenDialog(choose) == JFileChooser.APPROVE_OPTION) {
                    path = file.getSelectedFile().getAbsolutePath();
                    update();
                }
            });
            kind.addActionListener(event -> update());
            update();
        }

        private void update() {
            int type = kind.getSelectedIndex();
            ((java.awt.CardLayout) source.getLayout()).show(source, type == 1 ? "year" : type == 3 ? "image" : "blank");
            title.setEnabled(type != 0);
            String name = path.isBlank() ? "选择 PNG…" : Path.of(path).getFileName().toString();
            choose.setText(name.length() > 24 ? name.substring(0, 21) + "…" : name);
            choose.setToolTipText(path);
        }

        PaperPanels.Panel panel() {
            var type = PaperPanels.Type.values()[kind.getSelectedIndex() - 1];
            int yearIndex = years.getSelectedIndex();
            int year = type == PaperPanels.Type.NETWORK && yearIndex > 0 ? availableYears[yearIndex - 1] : 0;
            return new PaperPanels.Panel(type, year, title.getText().strip(), type == PaperPanels.Type.IMAGE ? path : "");
        }
    }
}
