package org.gephi.plugins.explorer3d;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.batik.parser.AWTPathProducer;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Reads the small SVG vocabulary emitted by the paper compositor. */
final class PaperArtwork {
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
    final Element root;
    final List<Item> items;
    final double width, height;
    final float pointsPerUnit;
    final double widthMm, heightMm;
    final List<String> families;

    record Item(Element element, AffineTransform position, Rectangle2D clip, String panel, String year) {
        String name() { return element.getLocalName(); }
        String attr(String key) { return element.getAttribute(key); }
        float number(String key, float fallback) { return PaperArtwork.number(element, key, fallback); }
        String style(String key, String fallback) {
            for (var node = element; node != null; node = node.getParentNode() instanceof Element parent ? parent : null)
                if (node.hasAttribute(key)) return node.getAttribute(key);
            return fallback;
        }
        Point2D point(String x, String y) {
            return position.transform(new Point2D.Float(number(x, 0), number(y, 0)), null);
        }
        Shape shape() throws IOException {
            Shape local = switch (name()) {
                case "rect" -> rectangle(element);
                case "circle" -> {
                    float r = number("r", 0);
                    yield new Ellipse2D.Float(number("cx", 0) - r, number("cy", 0) - r, 2 * r, 2 * r);
                }
                case "line" -> new Line2D.Float(number("x1", 0), number("y1", 0), number("x2", 0), number("y2", 0));
                case "polygon" -> polygon(attr("points"));
                case "path" -> AWTPathProducer.createShape(new StringReader(attr("d")),
                    style("fill-rule", "").equals("evenodd") ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                default -> throw new IllegalArgumentException("Unsupported paper shape " + name());
            };
            return position.createTransformedShape(local);
        }
    }

    private PaperArtwork(Element root, PaperSvg.Document document) {
        this.root = root;
        double[] viewBox = values(root.getAttribute("viewBox"));
        width = viewBox[2];
        height = viewBox[3];
        widthMm = document.widthMm();
        heightMm = document.heightMm();
        pointsPerUnit = (float) (widthMm / width * 72 / 25.4);
        families = java.util.Arrays.stream(root.getAttribute("font-family").split(",")).map(String::strip).toList();
        var found = new ArrayList<Item>();
        var clips = root.getElementsByTagNameNS(root.getNamespaceURI(), "clipPath");
        Element clipRect = clips.getLength() == 0 ? null : (Element) ((Element) clips.item(0))
            .getElementsByTagNameNS(root.getNamespaceURI(), "rect").item(0);
        Shape clip = clipRect == null ? null : rectangle(clipRect);
        collect(root, new AffineTransform(), null, "a", root.getAttribute("data-year"), clip, found);
        items = List.copyOf(found);
    }

    static PaperArtwork read(PaperSvg.Document document) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var tree = factory.newDocumentBuilder().parse(new InputSource(new StringReader(document.svg())));
            return new PaperArtwork(tree.getDocumentElement(), document);
        } catch (IOException | SAXException | ParserConfigurationException ex) {
            throw new IllegalArgumentException("无法检查成图 SVG", ex);
        }
    }

    private static void collect(Element element, AffineTransform parent, Rectangle2D inheritedClip,
                                String panel, String year, Shape clip, List<Item> items) {
        String name = element.getLocalName();
        String id = element.getAttribute("id");
        if (element.hasAttribute("data-panel-type")) {
            panel = id.substring("panel-".length());
            year = element.getAttribute("data-year");
        }
        var transform = parent;
        if (element.hasAttribute("transform")) {
            transform = new AffineTransform(parent);
            double[] offset = values(element.getAttribute("transform").replace("translate(", "").replace(")", ""));
            transform.translate(offset[0], offset[1]);
        }
        var visibleClip = "url(#plot-clip)".equals(element.getAttribute("clip-path")) && clip != null
            ? transform.createTransformedShape(clip).getBounds2D() : inheritedClip;
        switch (name) {
            case "svg", "g" -> {
                for (var node = element.getFirstChild(); node != null; node = node.getNextSibling())
                    if (node instanceof Element child) collect(child, transform, visibleClip, panel, year, clip, items);
            }
            case "text", "rect", "circle", "line", "polygon", "path", "image" ->
                items.add(new Item(element, transform, visibleClip, panel, year));
            default -> { }
        }
    }

    TextLayout layout(Item item) {
        String value = item.element.getTextContent();
        if (value.isEmpty()) return null;
        float size = item.number("font-size", 12);
        int style = bold(item) ? Font.BOLD : Font.PLAIN;
        var text = new java.text.AttributedString(value);
        List<Font> fonts = families.stream().map(family -> new Font(family, style, 12).deriveFont(size)).toList();
        var defaultFont = new Font(Font.SANS_SERIF, style, 12).deriveFont(size);
        for (int start = 0; start < value.length();) {
            int cp = value.codePointAt(start);
            Font use = fonts.stream().filter(font -> font.canDisplay(cp)).findFirst().orElse(defaultFont);
            int end = start + Character.charCount(cp);
            text.addAttribute(java.awt.font.TextAttribute.FONT, use, start, end);
            start = end;
        }
        return new TextLayout(text.getIterator(), FONT_CONTEXT);
    }

    static boolean bold(Item item) { return "bold".equals(item.style("font-weight", "normal")); }

    static double textX(Item item, double advance) {
        double x = item.point("x", "y").getX();
        return switch (item.attr("text-anchor")) {
            case "middle" -> x - advance / 2;
            case "end" -> x - advance;
            default -> x;
        };
    }

    private static Shape rectangle(Element element) {
        float x = number(element, "x", 0), y = number(element, "y", 0);
        float width = number(element, "width", 0), height = number(element, "height", 0);
        float radius = number(element, "rx", 0);
        return radius > 0 ? new RoundRectangle2D.Float(x, y, width, height, radius * 2, radius * 2)
            : new Rectangle2D.Float(x, y, width, height);
    }

    private static Shape polygon(String definition) {
        double[] values = values(definition);
        var shape = new Path2D.Double();
        shape.moveTo(values[0], values[1]);
        for (int i = 2; i < values.length; i += 2) shape.lineTo(values[i], values[i + 1]);
        shape.closePath();
        return shape;
    }

    private static float number(Element element, String key, float fallback) {
        return element.hasAttribute(key) ? Float.parseFloat(element.getAttribute(key)) : fallback;
    }

    static double[] values(String text) {
        return java.util.Arrays.stream(text.strip().split("[,\\s]+")).mapToDouble(Double::parseDouble).toArray();
    }
}
