package org.gephi.plugins.explorer3d;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.text.ParseException;
import java.util.List;
import javax.swing.*;

final class PaperStyleDialog {
    private static final List<String> PRESETS = List.of("current", "journal", "grayscale");
    private static final List<String> FONTS = List.of("yahei", "simsun", "arial", "times");

    private PaperStyleDialog() { }

    static PaperAppearance edit(Component parent, PaperAppearance current) {
        var panel = new JPanel(new GridLayout(0, 2, 10, 6));
        var themes = new JComboBox<>(PRESETS.stream().map(PaperAppearance.PRESETS::get).toArray(String[]::new));
        themes.setSelectedIndex(PRESETS.indexOf(current.preset()));
        var fonts = new JComboBox<>(new String[]{"微软雅黑", "宋体", "Arial", "Times New Roman"});
        fonts.setSelectedIndex(FONTS.indexOf(current.font()));
        row(panel, "配色", themes);
        row(panel, "字体", fonts);
        var useMargin = new JCheckBox("自定义", current.marginMm() != null);
        var margin = new JSpinner(new SpinnerNumberModel(current.marginMm() == null ? 4d : current.marginMm(),
            2d, 16d, .5d));
        var marginInput = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        marginInput.add(useMargin);
        marginInput.add(margin);
        margin.setEnabled(useMargin.isSelected());
        useMargin.addActionListener(event -> margin.setEnabled(useMargin.isSelected()));
        row(panel, "页边距（mm）", marginInput);
        var node = new InkPicker("重点节点颜色", current.keyNodeColor());
        var edge = new InkPicker("重点关系颜色", current.focusEdgeColor());
        row(panel, "重点节点", node);
        row(panel, "重点关系", edge);
        Runnable refresh = () -> {
            var theme = new PaperAppearance(PRESETS.get(themes.getSelectedIndex()), "yahei", null, null, null);
            node.update(theme.nodeInk(), !theme.grayscale());
            edge.update(theme.focusInk(), !theme.grayscale());
        };
        themes.addActionListener(event -> refresh.run());
        refresh.run();
        while (JOptionPane.showConfirmDialog(parent, panel, "论文样式", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                if (useMargin.isSelected()) margin.commitEdit();
                return new PaperAppearance(PRESETS.get(themes.getSelectedIndex()), FONTS.get(fonts.getSelectedIndex()),
                    useMargin.isSelected() ? ((Number) margin.getValue()).doubleValue() : null, node.value(), edge.value());
            } catch (ParseException | IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(parent, "请输入 2 至 16 mm 的页边距。", "页边距", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    private static void row(JPanel panel, String name, JComponent input) {
        var label = new JLabel(name);
        label.setLabelFor(input);
        panel.add(label);
        panel.add(input);
    }

    private static final class InkPicker extends JPanel {
        private final JCheckBox custom = new JCheckBox("自定义");
        private final JButton pick = new JButton();
        private Integer color;
        private int inherited;

        InkPicker(String title, Integer color) {
            super(new FlowLayout(FlowLayout.LEFT, 5, 0));
            this.color = color;
            custom.setSelected(color != null);
            pick.setToolTipText(title);
            pick.addActionListener(event -> {
                Color chosen = JColorChooser.showDialog(this, title, new Color(shown()));
                if (chosen != null) {
                    this.color = chosen.getRGB() & 0xffffff;
                    refreshButton();
                }
            });
            custom.addActionListener(event -> refreshButton());
            add(custom);
            add(pick);
        }

        Integer value() {
            if (!custom.isSelected()) return null;
            return custom.isEnabled() ? shown() : color;
        }
        private int shown() { return custom.isEnabled() && custom.isSelected() && color != null ? color : inherited; }
        void update(int inherited, boolean enabled) {
            this.inherited = inherited;
            custom.setEnabled(enabled);
            refreshButton();
        }

        private void refreshButton() {
            pick.setEnabled(custom.isEnabled() && custom.isSelected());
            pick.setText(PaperAppearance.hex(shown()));
            pick.setIcon(new Swatch(shown()));
        }
    }

    private record Swatch(int color) implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(new Color(color));
            g.fillRect(x, y, 14, 12);
            g.setColor(Color.GRAY);
            g.drawRect(x, y, 13, 11);
        }
    }
}
