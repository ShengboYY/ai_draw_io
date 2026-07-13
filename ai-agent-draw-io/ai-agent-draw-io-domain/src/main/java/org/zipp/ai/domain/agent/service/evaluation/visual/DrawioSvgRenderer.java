package org.zipp.ai.domain.agent.service.evaluation.visual;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic renderer for Eval evidence; it intentionally supports the common mxCell geometry subset. */
public class DrawioSvgRenderer implements IDiagramImageRenderer {
    public static final String VERSION = "drawio-svg-renderer-v1";
    private static final int PADDING = 32;
    private static final int MAX_CELLS = 2_000;
    private static final int MAX_DIMENSION = 8_192;

    @Override
    public RenderedDiagram render(String canvasXml) {
        if (StringUtils.isBlank(canvasXml) || canvasXml.length() > 4_000_000) {
            throw new IllegalArgumentException("canvas XML is missing or too large");
        }
        if (StringUtils.containsIgnoreCase(canvasXml, "<!DOCTYPE") || StringUtils.containsIgnoreCase(canvasXml, "<!ENTITY")) {
            throw new IllegalArgumentException("external XML declarations are not allowed");
        }
        try {
            Element model = graphModel(DocumentHelper.parseText(canvasXml).getRootElement());
            Element root = model.element("root");
            if (root == null) throw new IllegalArgumentException("mxGraphModel root is missing");
            List<Element> cells = root.elements("mxCell");
            if (cells.size() > MAX_CELLS) throw new IllegalArgumentException("canvas has too many cells");
            Map<String, Box> boxes = boxes(cells);
            int width = dimension(boxes.values().stream().mapToDouble(box -> box.x + box.width).max().orElse(640D));
            int height = dimension(boxes.values().stream().mapToDouble(box -> box.y + box.height).max().orElse(360D));
            StringBuilder body = new StringBuilder();
            edges(cells, boxes, body);
            vertices(cells, boxes, body);
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                    + "\" viewBox=\"0 0 " + width + " " + height + "\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/>"
                    + body + "</svg>";
            return new RenderedDiagram(svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml", VERSION, width, height);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid draw.io XML", e);
        }
    }

    private Element graphModel(Element root) {
        if ("mxGraphModel".equals(root.getName())) return root;
        Element direct = root.element("mxGraphModel");
        if (direct != null) return direct;
        Element diagram = root.element("diagram");
        if (diagram != null && diagram.element("mxGraphModel") != null) return diagram.element("mxGraphModel");
        throw new IllegalArgumentException("mxGraphModel is missing");
    }

    private Map<String, Box> boxes(List<Element> cells) {
        Map<String, Box> result = new HashMap<>();
        for (Element cell : cells) {
            if (!"1".equals(cell.attributeValue("vertex"))) continue;
            Element geometry = cell.element("mxGeometry");
            if (geometry == null) continue;
            result.put(cell.attributeValue("id"), new Box(number(geometry, "x", 0), number(geometry, "y", 0),
                    Math.max(1, number(geometry, "width", 120)), Math.max(1, number(geometry, "height", 60))));
        }
        return result;
    }

    private void edges(List<Element> cells, Map<String, Box> boxes, StringBuilder svg) {
        cells.stream().filter(cell -> "1".equals(cell.attributeValue("edge")))
                .sorted(Comparator.comparing(cell -> StringUtils.defaultString(cell.attributeValue("id"))))
                .forEach(cell -> {
                    Box source = boxes.get(cell.attributeValue("source")); Box target = boxes.get(cell.attributeValue("target"));
                    if (source == null || target == null) return;
                    Map<String, String> style = style(cell.attributeValue("style"));
                    svg.append("<line x1=\"").append(source.cx()).append("\" y1=\"").append(source.cy())
                            .append("\" x2=\"").append(target.cx()).append("\" y2=\"").append(target.cy())
                            .append("\" stroke=\"").append(color(style.get("strokeColor"), "#64748b"))
                            .append("\" stroke-width=\"").append(number(style.get("strokeWidth"), 2D, 1D, 12D)).append("\"")
                            .append("1".equals(style.get("dashed")) ? " stroke-dasharray=\"6 4\"" : "").append("/>");
                });
    }

    private void vertices(List<Element> cells, Map<String, Box> boxes, StringBuilder svg) {
        List<Element> vertices = new ArrayList<>(cells.stream().filter(cell -> "1".equals(cell.attributeValue("vertex"))).toList());
        vertices.sort(Comparator.comparing(cell -> StringUtils.defaultString(cell.attributeValue("id"))));
        for (Element cell : vertices) {
            Box box = boxes.get(cell.attributeValue("id")); if (box == null) continue;
            Map<String, String> style = style(cell.attributeValue("style"));
            String fill = "none".equalsIgnoreCase(style.get("fillColor")) ? "none" : color(style.get("fillColor"), "#f8fafc");
            String stroke = "none".equalsIgnoreCase(style.get("strokeColor")) ? "none" : color(style.get("strokeColor"), "#334155");
            String common = " fill=\"" + fill + "\" stroke=\"" + stroke + "\" stroke-width=\""
                    + number(style.get("strokeWidth"), 2D, 0D, 12D) + "\"";
            String shape = StringUtils.defaultString(style.get("shape"));
            if ("ellipse".equals(shape)) {
                svg.append("<ellipse cx=\"").append(box.cx()).append("\" cy=\"").append(box.cy()).append("\" rx=\"")
                        .append(box.width / 2D).append("\" ry=\"").append(box.height / 2D).append("\"").append(common).append("/>");
            } else if ("rhombus".equals(shape)) {
                svg.append("<polygon points=\"").append(box.cx()).append(',').append(box.y).append(' ')
                        .append(box.x + box.width).append(',').append(box.cy()).append(' ').append(box.cx()).append(',').append(box.y + box.height)
                        .append(' ').append(box.x).append(',').append(box.cy()).append("\"").append(common).append("/>");
            } else {
                double radius = "1".equals(style.get("rounded")) ? 8D : 0D;
                svg.append("<rect x=\"").append(box.x).append("\" y=\"").append(box.y).append("\" width=\"")
                        .append(box.width).append("\" height=\"").append(box.height).append("\" rx=\"").append(radius).append("\"")
                        .append(common).append("/>");
            }
            svg.append("<text x=\"").append(box.cx()).append("\" y=\"").append(box.cy())
                    .append("\" text-anchor=\"middle\" dominant-baseline=\"middle\" font-family=\"Arial,sans-serif\" font-size=\"")
                    .append(number(style.get("fontSize"), 14D, 6D, 72D)).append("\" fill=\"")
                    .append(color(style.get("fontColor"), "#0f172a")).append("\">")
                    .append(escape(StringUtils.defaultString(cell.attributeValue("value")).replaceAll("<[^>]+>", ""))).append("</text>");
        }
    }

    private int dimension(double maximum) { return Math.min(MAX_DIMENSION, Math.max(128, (int) Math.ceil(maximum) + PADDING)); }
    private double number(Element geometry, String name, double fallback) { try { return Double.parseDouble(StringUtils.defaultIfBlank(geometry.attributeValue(name), String.valueOf(fallback))); } catch (NumberFormatException e) { return fallback; } }
    private double number(String value, double fallback, double minimum, double maximum) { try { return Math.max(minimum, Math.min(maximum, Double.parseDouble(value))); } catch (Exception e) { return fallback; } }
    private Map<String, String> style(String value) { Map<String, String> result = new HashMap<>(); for (String token : StringUtils.defaultString(value).split(";")) { int split = token.indexOf('='); if (split > 0) result.put(token.substring(0, split), token.substring(split + 1)); else if ("ellipse".equals(token) || "rhombus".equals(token)) result.put("shape", token); } return result; }
    private String color(String value, String fallback) { return value != null && value.matches("#[0-9A-Fa-f]{3,8}|[A-Za-z]{3,20}") ? value : fallback; }
    private String escape(String value) { return StringUtils.defaultString(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private record Box(double x, double y, double width, double height) { double cx() { return x + width / 2D; } double cy() { return y + height / 2D; } }
}
