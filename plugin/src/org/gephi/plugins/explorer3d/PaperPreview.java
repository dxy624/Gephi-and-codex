package org.gephi.plugins.explorer3d;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;

/** Rasterizes the complete SVG that will be delivered by the editor. */
final class PaperPreview extends ImageTranscoder {
    private BufferedImage image;

    static BufferedImage render(PaperSvg.Document document, int width) {
        if (width < 100 || width > 2400) throw new IllegalArgumentException("预览宽度须在 100–2400 像素之间");
        var preview = new PaperPreview();
        preview.addTranscodingHint(KEY_WIDTH, (float) width);
        preview.addTranscodingHint(KEY_MAX_HEIGHT, 3200f);
        try {
            preview.transcode(new TranscoderInput(new StringReader(document.svg())), null);
            return preview.image;
        } catch (TranscoderException ex) {
            throw new IllegalStateException("成图预览失败：" + ex.getMessage(), ex);
        }
    }

    static Map<String, Object> save(PaperSvg.Document document, String file, int width) {
        Path output = Path.of(file);
        if (!output.isAbsolute() || !file.toLowerCase(Locale.ROOT).endsWith(".png"))
            throw new IllegalArgumentException("An absolute .png path is required");
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists; choose a new file name");
        BufferedImage image = render(document, width);
        try {
            Path temporary = Files.createTempFile(output.getParent(), ".gephi-preview-", ".tmp");
            try {
                if (!ImageIO.write(image, "png", temporary.toFile())) throw new IOException("PNG writer unavailable");
                Files.move(temporary, output);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("PNG preview export failed: " + ex.getMessage(), ex);
        }
        var result = new LinkedHashMap<>(document.metrics());
        result.put("success", true);
        result.put("path", file);
        result.put("width_px", image.getWidth());
        result.put("height_px", image.getHeight());
        result.put("width_mm", document.widthMm());
        result.put("height_mm", document.heightMm());
        return result;
    }

    @Override public BufferedImage createImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    @Override public void writeImage(BufferedImage image, TranscoderOutput output) {
        this.image = image;
    }
}
