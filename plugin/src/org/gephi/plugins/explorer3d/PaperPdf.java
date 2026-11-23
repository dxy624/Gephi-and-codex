package org.gephi.plugins.explorer3d;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

final class PaperPdf {
    private PaperPdf() { }

    static int write(PaperArtwork art, OutputStream output) throws IOException {
        try (var document = new PDDocument(); var fonts = new Fonts(document)) {
            float scale = art.pointsPerUnit;
            float pageHeight = (float) (art.height * scale);
            var page = new PDPage(new PDRectangle((float) (art.width * scale), pageHeight));
            document.addPage(page);
            document.getDocumentInformation().setCreator("Gephi 3D Explorer");
            var opacity = new HashMap<Float, PDExtendedGraphicsState>();
            int outlined = 0;
            try (var stream = new PDPageContentStream(document, page)) {
                stream.transform(new Matrix(scale, 0, 0, -scale, 0, pageHeight));
                for (var item : art.items) {
                    stream.saveGraphicsState();
                    try {
                        if (item.clip() != null) {
                            var clip = item.clip();
                            stream.addRect(f(clip.getX()), f(clip.getY()), f(clip.getWidth()), f(clip.getHeight()));
                            stream.clip();
                        }
                        float alpha = item.number("opacity", 1);
                        if (alpha < 1) stream.setGraphicsStateParameters(opacity.computeIfAbsent(alpha, value -> {
                            var state = new PDExtendedGraphicsState();
                            state.setStrokingAlphaConstant(value);
                            state.setNonStrokingAlphaConstant(value);
                            return state;
                        }));
                        switch (item.name()) {
                            case "text" -> { if (text(stream, art, item, fonts)) outlined++; }
                            case "image" -> image(document, stream, item);
                            default -> shape(stream, item);
                        }
                    } finally {
                        stream.restoreGraphicsState();
                    }
                }
            }
            document.save(output);
            return outlined;
        }
    }

    private static void shape(PDPageContentStream stream, PaperArtwork.Item item) throws IOException {
        Color fill = color(item.style("fill", "#000000"));
        Color stroke = color(item.style("stroke", "none"));
        if (fill == null && stroke == null) return;
        if (fill != null) stream.setNonStrokingColor(fill);
        if (stroke != null) {
            stream.setStrokingColor(stroke);
            stream.setLineWidth(Float.parseFloat(item.style("stroke-width", "1")));
            stream.setLineCapStyle(item.style("stroke-linecap", "butt").equals("round") ? 1 : 0);
            if (!item.style("stroke-dasharray", "none").equals("none")) {
                double[] dash = PaperArtwork.values(item.style("stroke-dasharray", "none"));
                var pattern = new float[dash.length];
                for (int i = 0; i < dash.length; i++) pattern[i] = f(dash[i]);
                stream.setLineDashPattern(pattern, 0);
            }
        }
        var shape = item.shape();
        if (shape.getPathIterator(null).isDone()) return;
        appendPath(stream, shape);
        boolean evenOdd = item.style("fill-rule", "").equals("evenodd");
        if (fill != null && stroke != null) {
            if (evenOdd) stream.fillAndStrokeEvenOdd();
            else stream.fillAndStroke();
        } else if (stroke != null) stream.stroke();
        else if (evenOdd) stream.fillEvenOdd();
        else stream.fill();
    }

    private static Color color(String value) { return value.equals("none") ? null : Color.decode(value); }

    private static boolean text(PDPageContentStream stream, PaperArtwork art, PaperArtwork.Item item, Fonts fonts)
            throws IOException {
        String content = item.element().getTextContent();
        if (content.isBlank()) return false;
        float size = item.number("font-size", 12);
        var runs = fonts.runs(content, art.families, PaperArtwork.bold(item));
        stream.setNonStrokingColor(color(item.style("fill", "#000000")));
        float y = f(item.point("x", "y").getY());
        if (runs.isEmpty()) {
            var layout = art.layout(item);
            var translated = AffineTransform.getTranslateInstance(PaperArtwork.textX(item, layout.getAdvance()), y);
            appendPath(stream, layout.getOutline(translated));
            stream.fill();
            return true;
        }
        float advance = 0;
        for (Run run : runs) advance += run.width(size);
        float x = f(PaperArtwork.textX(item, advance));
        for (Run run : runs) {
            stream.beginText();
            stream.setFont(run.font, size);
            stream.setTextMatrix(new Matrix(1, 0, 0, -1, x, y));
            stream.showText(run.text);
            stream.endText();
            x += run.width(size);
        }
        return false;
    }

    private static void image(PDDocument document, PDPageContentStream stream, PaperArtwork.Item item) throws IOException {
        String uri = item.element().getAttributeNS("http://www.w3.org/1999/xlink", "href");
        String prefix = "data:image/png;base64,";
        if (!uri.startsWith(prefix)) throw new IOException("缺少内嵌 PNG");
        var bitmap = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(uri.substring(prefix.length()))));
        if (bitmap == null) throw new IOException("无法解码内嵌 PNG");
        float boxWidth = item.number("width", 0), boxHeight = item.number("height", 0);
        float ratio = Math.min(boxWidth / bitmap.getWidth(), boxHeight / bitmap.getHeight());
        float width = ratio * bitmap.getWidth(), height = ratio * bitmap.getHeight();
        var point = item.point("x", "y");
        float x = f(point.getX()) + (boxWidth - width) / 2;
        float y = f(point.getY()) + (boxHeight - height) / 2;
        stream.drawImage(LosslessFactory.createFromImage(document, bitmap), new Matrix(width, 0, 0, -height, x, y + height));
    }

    private static void appendPath(PDPageContentStream stream, Shape shape) throws IOException {
        float[] p = new float[6];
        float x = 0, y = 0, startX = 0, startY = 0;
        for (var path = shape.getPathIterator(null); !path.isDone(); path.next()) {
            switch (path.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> { stream.moveTo(p[0], p[1]); x = startX = p[0]; y = startY = p[1]; }
                case PathIterator.SEG_LINETO -> { stream.lineTo(p[0], p[1]); x = p[0]; y = p[1]; }
                case PathIterator.SEG_QUADTO -> {
                    stream.curveTo(x + 2 * (p[0] - x) / 3, y + 2 * (p[1] - y) / 3,
                        p[2] + 2 * (p[0] - p[2]) / 3, p[3] + 2 * (p[1] - p[3]) / 3, p[2], p[3]);
                    x = p[2]; y = p[3];
                }
                case PathIterator.SEG_CUBICTO -> { stream.curveTo(p[0], p[1], p[2], p[3], p[4], p[5]); x = p[4]; y = p[5]; }
                case PathIterator.SEG_CLOSE -> { stream.closePath(); x = startX; y = startY; }
                default -> throw new IOException("未知矢量路径");
            }
        }
    }

    private static float f(double value) { return (float) value; }

    private record Run(PDType0Font font, String text) {
        float width(float size) throws IOException { return font.getStringWidth(text) * size / 1000; }
    }

    private static final class Fonts implements AutoCloseable {
        private final PDDocument document;
        private final Map<String, PDType0Font> loaded = new HashMap<>();
        private final List<TrueTypeCollection> collections = new ArrayList<>();
        private final Path directory = Path.of(System.getenv().getOrDefault("WINDIR", "C:/Windows"), "Fonts");

        Fonts(PDDocument document) { this.document = document; }

        List<Run> runs(String text, List<String> families, boolean bold) throws IOException {
            var filenames = new java.util.LinkedHashSet<String>();
            for (String name : families) switch (name) {
                case "Microsoft YaHei" -> filenames.add(bold ? "msyhbd.ttc" : "msyh.ttc");
                case "SimSun" -> filenames.add("simsun.ttc");
                case "Times New Roman" -> filenames.add(bold ? "timesbd.ttf" : "times.ttf");
                case "Arial" -> filenames.add(bold ? "arialbd.ttf" : "arial.ttf");
                default -> { }
            }
            filenames.add(bold ? "msyhbd.ttc" : "msyh.ttc");
            filenames.add("simsun.ttc");
            filenames.add(bold ? "arialbd.ttf" : "arial.ttf");
            var result = new ArrayList<Run>();
            PDType0Font last = null;
            var run = new StringBuilder();
            for (int start = 0; start < text.length();) {
                int cp = text.codePointAt(start);
                String letter = Character.toString(cp);
                var font = find(filenames, letter);
                if (font == null) return List.of();
                if (last != null && last != font) {
                    result.add(new Run(last, run.toString()));
                    run.setLength(0);
                }
                last = font;
                run.append(letter);
                start += Character.charCount(cp);
            }
            if (last != null) result.add(new Run(last, run.toString()));
            return result;
        }

        private PDType0Font find(Iterable<String> filenames, String letter) throws IOException {
            for (String filename : filenames) {
                if (!loaded.containsKey(filename)) loaded.put(filename, load(filename));
                PDType0Font font = loaded.get(filename);
                if (font == null) continue;
                try {
                    font.getStringWidth(letter);
                    return font;
                } catch (IllegalArgumentException ex) {
                    // The currently selected family does not have this glyph.
                }
            }
            return null;
        }

        private PDType0Font load(String name) {
            var file = directory.resolve(name).toFile();
            if (!Files.isRegularFile(file.toPath())) return null;
            try {
                if (!name.endsWith(".ttc")) return PDType0Font.load(document, file);
                var collection = new TrueTypeCollection(file);
                collections.add(collection);
                var first = new TrueTypeFont[1];
                collection.processAllFonts(candidate -> { if (first[0] == null) first[0] = candidate; });
                return first[0] == null ? null : PDType0Font.load(document, first[0], true);
            } catch (IOException | IllegalArgumentException ex) {
                return null;
            }
        }

        @Override public void close() throws IOException {
            for (var collection : collections) collection.close();
        }
    }
}
