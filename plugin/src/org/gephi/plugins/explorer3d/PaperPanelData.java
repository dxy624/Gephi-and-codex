package org.gephi.plugins.explorer3d;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import javax.imageio.ImageIO;

final class PaperPanelData {
    private static final long MAX_IMAGE_BYTES = 8_000_000;
    private static final long MAX_PIXELS = 12_000_000;

    record YearCount(int year, long count) { }
    record Illustration(int width, int height, String data) { }

    private PaperPanelData() { }

    static List<YearCount> counts(Scene scene, List<PaperPanels.Panel> panels, TreeSet<Integer> availableYears) {
        var years = new TreeSet<Integer>();
        for (var panel : panels) if (panel.type() == PaperPanels.Type.NETWORK && panel.year() > 0)
            years.add(panel.year());
        if (years.isEmpty()) years.addAll(availableYears);
        if (years.isEmpty()) return List.of(new YearCount(0, scene.edges.stream().filter(scene::visible).count()));
        if (years.size() > 24)
            throw new IllegalArgumentException("年度统计最多显示 24 年。请选择要比较的年份作为网络子图。");
        var result = new ArrayList<YearCount>();
        var yearly = scene.copy();
        yearly.referenceYear = 0;
        for (int year : years) {
            yearly.activeYear = year;
            yearly.refreshViewCaches();
            result.add(new YearCount(year, yearly.edges.stream().filter(yearly::visible).count()));
        }
        return List.copyOf(result);
    }

    static Illustration image(String file, PaperAppearance appearance) {
        Path path = Path.of(file);
        if (!path.isAbsolute() || !file.toLowerCase(Locale.ROOT).endsWith(".png"))
            throw new IllegalArgumentException("示意图需要本地 PNG 文件的绝对路径");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_IMAGE_BYTES)
                throw new IllegalArgumentException("示意图须为有效 PNG，文件大小最多 8 MB");
            var reader = ImageIO.getImageReadersByFormatName("png").next();
            BufferedImage image;
            try (var input = ImageIO.createImageInputStream(path.toFile())) {
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS)
                    throw new IllegalArgumentException("示意图最多支持 1200 万像素");
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
            if (appearance.grayscale()) image = grayscale(image, appearance);
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG 编码器不可用");
            return new Illustration(image.getWidth(), image.getHeight(),
                "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取 PNG 示意图 " + path.getFileName(), ex);
        }
    }

    private static BufferedImage grayscale(BufferedImage image, PaperAppearance appearance) {
        var result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] line = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, line.length, 1, line, 0, line.length);
            for (int x = 0; x < line.length; x++)
                line[x] = (line[x] & 0xff000000) | appearance.display(line[x] & 0xffffff);
            result.setRGB(0, y, line.length, 1, line, 0, line.length);
        }
        return result;
    }
}
