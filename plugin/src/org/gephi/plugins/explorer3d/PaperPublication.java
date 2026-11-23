package org.gephi.plugins.explorer3d;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;

final class PaperPublication {
    private static final com.google.gson.Gson JSON = new com.google.gson.GsonBuilder()
        .setPrettyPrinting().disableHtmlEscaping().create();

    private PaperPublication() { }

    static PaperAudit.Report inspect(PaperSvg.Document figure, double minimumPoints) {
        try {
            return PaperAudit.inspect(PaperArtwork.read(figure), figure, minimumPoints);
        } catch (IOException ex) {
            throw new IllegalStateException("成稿检查失败", ex);
        }
    }

    static Map<String, Object> save(PaperSvg.Document figure, String path, int dpi, double minimumPoints) {
        Path output = Path.of(path);
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        String type = name.substring(name.lastIndexOf('.') + 1);
        if (!output.isAbsolute() || !List.of("svg", "pdf", "tif", "tiff", "zip").contains(type))
            throw new IllegalArgumentException("请选择新的 SVG、PDF、TIFF 或 ZIP 绝对路径");
        if (Files.exists(output)) throw new IllegalArgumentException("文件已存在，请选择新文件名");
        if (dpi != 300 && dpi != 600) throw new IllegalArgumentException("请选择 300 或 600 dpi");
        var art = PaperArtwork.read(figure);
        try {
            var audit = PaperAudit.inspect(art, figure, minimumPoints);
            var result = new LinkedHashMap<>(figure.metrics());
            result.put("audit", audit);
            result.put("width_mm", figure.widthMm());
            result.put("height_mm", figure.heightMm());
            result.put("dpi", dpi);
            result.put("path", path);
            result.put("success", true);
            Path temporary = Files.createTempFile(output.getParent(), ".gephi-publication-", ".tmp");
            try {
                switch (type) {
                    case "svg" -> Files.writeString(temporary, figure.svg());
                    case "pdf" -> {
                        try (var stream = Files.newOutputStream(temporary)) {
                            result.put("outlined_labels", PaperPdf.write(art, stream));
                        }
                    }
                    case "tif", "tiff" -> tiff(figure, temporary, dpi);
                    case "zip" -> result.put("outlined_labels", bundle(figure, art, audit, temporary, dpi, minimumPoints));
                    default -> throw new IllegalArgumentException("未知投稿格式");
                }
                Files.move(temporary, output);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("投稿导出失败：" + ex.getMessage(), ex);
        }
    }

    private static void tiff(PaperSvg.Document figure, Path destination, int dpi) throws IOException {
        var image = PaperPreview.print(figure, dpi);
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        var parameters = writer.getDefaultWriteParam();
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameters.setCompressionType("LZW");
        var directory = TIFFDirectory.createFromMetadata(writer.getDefaultImageMetadata(
            ImageTypeSpecifier.createFromRenderedImage(image), parameters));
        var tags = BaselineTIFFTagSet.getInstance();
        for (int tag : new int[]{BaselineTIFFTagSet.TAG_X_RESOLUTION, BaselineTIFFTagSet.TAG_Y_RESOLUTION})
            directory.addTIFFField(new TIFFField(tags.getTag(tag), TIFFTag.TIFF_RATIONAL, 1, new long[][]{{dpi, 1}}));
        directory.addTIFFField(new TIFFField(tags.getTag(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT),
            BaselineTIFFTagSet.RESOLUTION_UNIT_INCH));
        try (var stream = ImageIO.createImageOutputStream(destination.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, directory.getAsMetadata()), parameters);
        } finally {
            writer.dispose();
        }
    }

    private static int bundle(PaperSvg.Document figure, PaperArtwork art, PaperAudit.Report audit, Path target,
                              int dpi, double minimumPoints) throws IOException {
        Path pdf = Files.createTempFile(target.getParent(), ".gephi-figure-", ".pdf");
        Path tiff = null;
        try {
            tiff = Files.createTempFile(target.getParent(), ".gephi-figure-", ".tiff");
            int outlined;
            try (var output = Files.newOutputStream(pdf)) {
                outlined = PaperPdf.write(art, output);
            }
            tiff(figure, tiff, dpi);
            try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
                var checksums = new StringBuilder();
                entry(zip, checksums, "figure.svg", figure.svg());
                file(zip, checksums, "figure.pdf", pdf);
                file(zip, checksums, "figure.tiff", tiff);
                entry(zip, checksums, "preflight.json", JSON.toJson(Map.of("minimum_font_pt", minimumPoints,
                    "outlined_labels", outlined, "report", audit)));
                var metadata = art.root.getElementsByTagNameNS(art.root.getNamespaceURI(), "metadata");
                String settings = metadata.getLength() == 0 ? "{}" : metadata.item(0).getTextContent();
                entry(zip, checksums, "figure-settings.json", JSON.toJson(Map.of("width_mm", art.widthMm,
                    "height_mm", art.heightMm, "tiff_dpi", dpi, "settings", com.google.gson.JsonParser.parseString(settings))));
                dataTables(art, zip, checksums);
                entry(zip, checksums, "README.txt", """
                    Gephi 3D Explorer · 论文图稿

                    figure.svg 是自包含的可编辑成图。网络、统计图和文字可分别修改；插图随文件保存。
                    figure.pdf 保留矢量线、形状和可嵌入字体的文字。无法嵌入的字形保留为矢量轮廓。
                    figure.tiff 按成图物理尺寸写入提交分辨率。右键属性或图像软件可查看 DPI。
                    preflight.json 是字号、裁切、遮挡和组图检查记录。
                    figure-settings.json 记录图面设置和统计范围。
                    nodes.csv、edges.csv 和 counts.csv 记录每个子图实际绘制的节点、边和统计值。
                    数值和地图信息以出图时实际的筛选和范围为准。节点的 x_mm/y_mm 为页面坐标，从左上角计量。
                    可在外部 SVG 编辑器中从 figure.svg 重导出同一版式。
                    SHA256SUMS 验证本包内文件是否与导出时一致。
                    """);
                zip.putNextEntry(new ZipEntry("SHA256SUMS"));
                zip.write(checksums.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return outlined;
        } finally {
            Files.deleteIfExists(pdf);
            if (tiff != null) Files.deleteIfExists(tiff);
        }
    }

    private static void dataTables(PaperArtwork art, ZipOutputStream zip, StringBuilder checksums) throws IOException {
        var nodes = new StringBuilder("panel,year,node_id,label,community,x_mm,y_mm\r\n");
        var edges = new StringBuilder("panel,year,edge_id,source,target,weight,focus,year_status\r\n");
        var counts = new StringBuilder("panel,year,count\r\n");
        double mm = art.widthMm / art.width;
        for (var item : art.items) {
            if (!item.attr("data-node-id").isBlank()) {
                var location = item.point("cx", "cy");
                row(nodes, item.panel(), item.year(), item.attr("data-node-id"), item.element().getTextContent(),
                    item.attr("data-community"), location.getX() * mm, location.getY() * mm);
            }
            if (item.name().equals("path") && !item.attr("data-edge-id").isBlank()) {
                row(edges, item.panel(), item.year(), item.attr("data-edge-id"), item.attr("data-source"),
                    item.attr("data-target"), Double.parseDouble(item.attr("data-weight")), item.attr("data-focus"),
                    item.attr("data-year-status"));
            }
            if (!item.attr("data-count").isBlank())
                row(counts, item.panel(), item.attr("data-year"), item.attr("data-count"));
        }
        entry(zip, checksums, "nodes.csv", nodes.toString());
        entry(zip, checksums, "edges.csv", edges.toString());
        entry(zip, checksums, "counts.csv", counts.toString());
    }

    private static void row(StringBuilder destination, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) destination.append(',');
            Object value = values[i];
            if (value instanceof Number) destination.append(value);
            else {
                String text = value.toString();
                String inspected = text.stripLeading();
                if (!inspected.isEmpty() && "=+-@".indexOf(inspected.charAt(0)) >= 0) text = "'" + text;
                destination.append('"').append(text.replace("\"", "\"\"")).append('"');
            }
        }
        destination.append("\r\n");
    }

    private static void entry(ZipOutputStream zip, StringBuilder sum, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
        sum.append(HexFormat.of().formatHex(digest().digest(bytes))).append("  ").append(name).append('\n');
    }

    private static void file(ZipOutputStream zip, StringBuilder sum, String name, Path source) throws IOException {
        var hash = digest();
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(source, new DigestOutputStream(zip, hash));
        zip.closeEntry();
        sum.append(HexFormat.of().formatHex(hash.digest())).append("  ").append(name).append('\n');
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
