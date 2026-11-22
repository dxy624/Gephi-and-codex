package org.gephi.plugins.explorer3d;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Edits a copy of the current scene and saves the exact document shown in preview. */
final class PaperEditor extends JDialog {
    private final ExplorerPanel panel;
    private final Scene draft;
    private final int viewWidth, viewHeight;
    private final JTextField title, direction, weightMeaning = new JTextField(18);
    private final JTextArea caption = new JTextArea(4, 18);
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"当前视图", "双年份并排"});
    private final JComboBox<Integer> leftYear, rightYear;
    private final JComboBox<String> zoom = new JComboBox<>(new String[]{"整页预览", "实际大小（估算）"});
    private final JSpinner width = new JSpinner(new SpinnerNumberModel(183.0, 60.0, 300.0, 1.0));
    private final JSpinner nodeScale = multiplier(), edgeScale = multiplier(), arrowScale = multiplier();
    private final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(6.8, 4.0, 18.0, .5));
    private final JCheckBox arrows = new JCheckBox("显示有向边箭头");
    private final JCheckBox autoCaption = new JCheckBox("附上年份、范围和地图说明", PaperOptions.DEFAULT.automaticCaption());
    private final JCheckBox dim = new JCheckBox("指定背景线不透明度");
    private final JSpinner context = new JSpinner(new SpinnerNumberModel(12, 0, 100, 1));
    private final JLabel image = new JLabel("", SwingConstants.CENTER);
    private final JScrollPane viewport = new JScrollPane(image);
    private final JLabel info = new JLabel("正在准备预览…");
    private final JButton save = new JButton("保存 SVG…"), legend = new JButton("编辑图例…");
    private final JButton annotate = new JButton("重点标注…");
    private final Timer timer = new Timer(250, event -> buildPreview());
    private Map<String, String> marks = Map.of();
    private PaperAppearance appearance = PaperAppearance.DEFAULT;
    private PaperSvg.Document document;
    private int generation;
    private boolean rendering, saving;

    static void open(ExplorerPanel panel, Scene draft) {
        var editor = new PaperEditor(panel, draft);
        editor.setVisible(true);
    }

    private PaperEditor(ExplorerPanel panel, Scene draft) {
        super(SwingUtilities.getWindowAncestor(panel), "论文图预览与导出", ModalityType.APPLICATION_MODAL);
        this.panel = panel;
        this.draft = draft;
        viewWidth = Math.min(8000, panel.paperViewWidth());
        viewHeight = Math.min(8000, panel.paperViewHeight());
        title = new JTextField(draft.geographyEnabled ? "企业地理关系图" : "网络关系图", 18);
        direction = new JTextField(PaperSvg.defaultDirection(draft), 18);
        arrows.setSelected(draft.showsArrows());
        Integer[] years = EdgeYears.available(draft).toArray(Integer[]::new);
        leftYear = new JComboBox<>(years);
        rightYear = new JComboBox<>(years);
        if (years.length < 2) {
            mode.removeItemAt(1);
            mode.setToolTipText("双年份组图需要至少两个年份的边数据");
        } else {
            int right = draft.activeYear != 0 ? draft.activeYear : years[years.length - 1];
            int left = draft.referenceYear != 0 ? draft.referenceYear
                : java.util.Arrays.stream(years).filter(year -> year < right).reduce((a, b) -> b)
                    .orElse(years[years.length - 1]);
            rightYear.setSelectedItem(right);
            leftYear.setSelectedItem(left);
        }

        var form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 7));
        row(form, "子图", mode);
        var yearInputs = new JPanel(new java.awt.GridLayout(1, 2, 5, 0));
        yearInputs.add(leftYear); yearInputs.add(rightYear);
        row(form, "左年 / 右年", yearInputs);
        var sizes = new JPanel(new java.awt.GridLayout(1, 3, 4, 0));
        sizes.add(width);
        sizes.add(action("85", () -> width.setValue(85.0)));
        sizes.add(action("183", () -> width.setValue(183.0)));
        row(form, "成图宽度（mm）", sizes);
        var paperStyle = new JButton("沿用当前…");
        paperStyle.addActionListener(event -> {
            var edited = PaperStyleDialog.edit(this, appearance);
            if (edited != null && !edited.equals(appearance)) {
                appearance = edited;
                paperStyle.setText(PaperAppearance.PRESETS.get(edited.preset()) + "…");
                change();
            }
        });
        row(form, "论文样式", paperStyle);
        row(form, "标题", title);
        row(form, "箭头含义", direction);
        row(form, "节点大小（×）", nodeScale);
        row(form, "线宽（×）", edgeScale);
        row(form, "箭头大小（×）", arrowScale);
        row(form, "", arrows);
        row(form, "基础字号（pt）", fontSize);
        row(form, "", dim);
        row(form, "不透明度（%）", context);
        row(form, "", annotate);
        row(form, "", legend);
        row(form, "", autoCaption);
        row(form, "边权含义", weightMeaning);
        caption.setLineWrap(true);
        caption.setWrapStyleWord(true);
        row(form, "自定义图注", new JScrollPane(caption));
        var controls = new JScrollPane(form);
        controls.setBorder(null);
        controls.setPreferredSize(new Dimension(345, 590));
        controls.getVerticalScrollBar().setUnitIncrement(18);

        viewport.getViewport().setBackground(new Color(0xe7e9ed));
        var preview = new JPanel(new BorderLayout(6, 6));
        var toolbar = new JPanel(new BorderLayout(6, 0));
        toolbar.add(zoom, BorderLayout.WEST);
        toolbar.add(info, BorderLayout.CENTER);
        preview.add(toolbar, BorderLayout.NORTH);
        preview.add(viewport, BorderLayout.CENTER);
        var footer = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        footer.add(action("关闭", this::dispose));
        footer.add(save);
        var content = new JPanel(new BorderLayout(9, 9));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        content.add(controls, BorderLayout.WEST);
        content.add(preview, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(save);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var screen = getGraphicsConfiguration().getBounds();
        setSize(Math.min(1190, screen.width - 65), Math.min(820, screen.height - 85));
        setLocationRelativeTo(panel);

        timer.setRepeats(false);
        mode.addActionListener(event -> { enableOptions(); change(); });
        leftYear.addActionListener(event -> change());
        rightYear.addActionListener(event -> change());
        zoom.addActionListener(event -> change());
        arrows.addActionListener(event -> change());
        autoCaption.addActionListener(event -> change());
        dim.addActionListener(event -> { enableOptions(); change(); });
        for (JSpinner input : new JSpinner[]{width, nodeScale, edgeScale, arrowScale, fontSize, context})
            input.addChangeListener(event -> change());
        for (var input : new javax.swing.text.JTextComponent[]{title, direction, caption, weightMeaning})
            input.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { change(); }
                @Override public void removeUpdate(DocumentEvent event) { change(); }
                @Override public void changedUpdate(DocumentEvent event) { change(); }
            });
        annotate.addActionListener(event -> editMarks());
        legend.addActionListener(event -> {
            if (document != null && ViewDialogs.editLegend(this, draft, document.keys())) change();
        });
        viewport.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { if (zoom.getSelectedIndex() == 0) change(); }
        });
        save.addActionListener(event -> save());
        enableOptions();
        change();
    }

    private void enableOptions() {
        boolean paired = mode.getSelectedIndex() == 1;
        leftYear.setEnabled(paired);
        rightYear.setEnabled(paired);
        context.setEnabled(dim.isSelected());
    }

    private void editMarks() {
        var values = new JTextArea(9, 34);
        var entries = new StringBuilder();
        marks.forEach((id, name) -> entries.append(id).append(name.isBlank() ? "" : " = " + name).append('\n'));
        values.setText(entries.toString());
        var body = new JPanel(new BorderLayout(4, 7));
        body.add(new JLabel("每行输入一个企业代码或节点 ID。可写成 002608 = 自定义名称"), BorderLayout.NORTH);
        body.add(new JScrollPane(values), BorderLayout.CENTER);
        JButton append = action("加入当前选中节点", () -> {
            String content = values.getText().stripTrailing();
            var existing = content.lines().map(line -> line.split("=", 2)[0].strip()).toList();
            String extra = String.join("\n", draft.selection.stream().filter(id -> !existing.contains(id)).toList());
            if (!extra.isEmpty()) values.setText(content.isEmpty() ? extra : content + "\n" + extra);
        });
        append.setEnabled(!draft.selection.isEmpty());
        body.add(append, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, body, "重点标注", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        var parsed = new LinkedHashMap<String, String>();
        values.getText().lines().filter(line -> !line.isBlank()).forEach(line -> {
            String[] parts = line.split("=", 2);
            parsed.put(parts[0].strip(), parts.length == 1 ? "" : parts[1].strip());
        });
        marks = parsed;
        annotate.setText(parsed.isEmpty() ? "重点标注…" : "重点标注（" + parsed.size() + "）…");
        change();
    }

    private void change() {
        generation++;
        document = null;
        save.setEnabled(false);
        legend.setEnabled(false);
        info.setText("正在更新预览…");
        timer.restart();
    }

    private void buildPreview() {
        if (rendering || !isDisplayable()) return;
        int request = generation;
        boolean paired = mode.getSelectedIndex() == 1;
        double widthMm = number(width);
        String figureTitle = title.getText(), directionLabel = direction.getText();
        PaperOptions options;
        try {
            options = new PaperOptions(number(nodeScale), number(edgeScale), number(arrowScale), arrows.isSelected(),
                number(fontSize), dim.isSelected() ? number(context) / 100 : -1, marks,
                autoCaption.isSelected(), caption.getText().strip(), weightMeaning.getText().strip(),
                paired ? (int) leftYear.getSelectedItem() : 0, paired ? (int) rightYear.getSelectedItem() : 0, appearance);
        } catch (IllegalArgumentException ex) {
            info.setText(ex.getMessage());
            image.setIcon(null);
            return;
        }
        Dimension space = viewport.getViewport().getExtentSize();
        boolean actual = zoom.getSelectedIndex() == 1;
        int dpi = getToolkit().getScreenResolution();
        rendering = true;
        new SwingWorker<Preview, Void>() {
            @Override protected Preview doInBackground() {
                PaperSvg.Document doc = PaperSvg.render(draft, viewWidth, viewHeight, widthMm, figureTitle, directionLabel, options);
                double pixels = actual ? widthMm * dpi / 25.4
                    : Math.min(space.width - 14, (space.height - 14) * doc.widthMm() / doc.heightMm());
                return new Preview(doc, PaperPreview.render(doc, (int) Math.max(100, Math.min(2400, pixels))));
            }

            @Override protected void done() {
                rendering = false;
                if (!isDisplayable()) return;
                if (request != generation) {
                    timer.restart();
                    return;
                }
                try {
                    Preview result = get();
                    document = result.document();
                    image.setIcon(new ImageIcon(result.image()));
                    info.setText(String.format(Locale.ROOT, "%.1f × %.1f mm · 未排入的重点标签 %s",
                        document.widthMm(), document.heightMm(), document.metrics().get("unplaced_priority_labels")));
                    legend.setEnabled(true);
                    save.setEnabled(!saving);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    info.setText(ex.getCause().getMessage());
                    image.setIcon(null);
                }
            }
        }.execute();
    }

    private void save() {
        if (document == null || saving) return;
        PaperSvg.Document ready = document;
        var chooser = new JFileChooser();
        chooser.setDialogTitle("保存可编辑的论文图");
        chooser.setFileFilter(new FileNameExtensionFilter("SVG 矢量图", "svg"));
        chooser.setSelectedFile(new java.io.File("network-paper.svg"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String file = chooser.getSelectedFile().getAbsolutePath();
        String path = file.toLowerCase(Locale.ROOT).endsWith(".svg") ? file : file + ".svg";
        saving = true;
        save.setEnabled(false);
        new SwingWorker<Map<String, Object>, Void>() {
            @Override protected Map<String, Object> doInBackground() { return ready.save(path); }
            @Override protected void done() {
                saving = false;
                save.setEnabled(document != null);
                try {
                    get();
                    panel.status.setText("已导出 SVG " + path);
                    JOptionPane.showMessageDialog(PaperEditor.this, "已保存\n" + path, "论文图", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    JOptionPane.showMessageDialog(PaperEditor.this, ex.getCause().getMessage(), "保存失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static JSpinner multiplier() {
        return new JSpinner(new SpinnerNumberModel(1.0, .25, 4.0, .1));
    }

    private static double number(JSpinner input) { return ((Number) input.getValue()).doubleValue(); }

    private static JButton action(String label, Runnable run) {
        JButton button = new JButton(label);
        button.addActionListener(event -> run.run());
        return button;
    }

    private static void row(JPanel owner, String name, JComponent component) {
        var constraints = new GridBagConstraints();
        constraints.gridy = GridBagConstraints.RELATIVE;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.gridx = 0;
        var label = new JLabel(name);
        label.setLabelFor(component);
        owner.add(label, constraints);
        constraints.gridx = 1;
        constraints.gridwidth = GridBagConstraints.REMAINDER;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        owner.add(component, constraints);
    }

    private record Preview(PaperSvg.Document document, BufferedImage image) { }
}
