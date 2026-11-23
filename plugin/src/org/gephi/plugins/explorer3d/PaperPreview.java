package org.gephi.plugins.explorer3d;

import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.batik.anim.dom.SVGOMDocument;
import org.apache.batik.bridge.Bridge;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.SVGImageElementBridge;
import org.apache.batik.ext.awt.image.GraphicsUtil;
import org.apache.batik.ext.awt.image.renderable.RedRable;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.util.ParsedURL;
import org.w3c.dom.Element;

/** Rasterizes the complete SVG that will be delivered by the editor. */
final class PaperPreview extends ImageTranscoder {
    private BufferedImage image;
    private boolean print;

    static BufferedImage render(PaperSvg.Document document, int width) {
        if (width < 100 || width > 2400) throw new IllegalArgumentException("预览宽度须在 100–2400 像素之间");
        return render(document, width, 0);
    }

    static BufferedImage print(PaperSvg.Document document, int dpi) {
        int width = (int) Math.round(document.widthMm() / 25.4 * dpi);
        int height = (int) Math.round(document.heightMm() / 25.4 * dpi);
        if (width > 9000 || height > 12000 || (long) width * height > 42_000_000)
            throw new IllegalArgumentException("光栅图过大，请减小版面或使用 300 dpi");
        return render(document, width, height);
    }

    private static BufferedImage render(PaperSvg.Document document, int width, int height) {
        var preview = new PaperPreview();
        preview.print = height > 0;
        preview.addTranscodingHint(KEY_WIDTH, (float) width);
        if (preview.print) {
            preview.addTranscodingHint(KEY_HEIGHT, (float) height);
            preview.addTranscodingHint(KEY_BACKGROUND_COLOR, java.awt.Color.WHITE);
        } else {
            preview.addTranscodingHint(KEY_MAX_HEIGHT, 3200f);
        }
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
        return new BufferedImage(width, height, print ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
    }

    @Override public void writeImage(BufferedImage image, TranscoderOutput output) {
        this.image = image;
    }

    @Override protected BridgeContext createBridgeContext(SVGOMDocument document) {
        return new BridgeContext(userAgent) {
            @Override public void registerSVGBridges() {
                super.registerSVGBridges();
                putBridge(new EmbeddedPngBridge());
            }
        };
    }

    // Gephi ships the SVG renderer without Batik's PNG codec. Decode the
    // document's embedded panels with Java ImageIO and keep Batik's positioning.
    private static final class EmbeddedPngBridge extends SVGImageElementBridge {
        private static final String PNG = "data:image/png;base64,";

        @Override public Bridge getInstance() { return new EmbeddedPngBridge(); }

        @Override protected GraphicsNode createImageGraphicsNode(BridgeContext context, Element element, ParsedURL url) {
            String href = element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            if (!href.startsWith(PNG)) throw new IllegalArgumentException("预览只读取已嵌入的 PNG 示意图");
            try (var data = new ByteArrayInputStream(Base64.getDecoder().decode(href.substring(PNG.length())))) {
                BufferedImage illustration = ImageIO.read(data);
                if (illustration == null) throw new IOException("内嵌 PNG 无法解码");
                var raster = new BufferedImage(illustration.getWidth(), illustration.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
                var graphics = raster.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.drawImage(illustration, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                return createRasterImageNode(context, element, new RedRable(GraphicsUtil.wrap(raster)), url);
            } catch (IOException ex) {
                throw new IllegalArgumentException("无法预览内嵌示意图", ex);
            }
        }
    }
}
