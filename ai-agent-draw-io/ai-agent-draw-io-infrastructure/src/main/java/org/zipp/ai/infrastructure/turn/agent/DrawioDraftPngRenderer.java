package org.zipp.ai.infrastructure.turn.agent;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasPointData;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless PNG renderer for the common mxCell subset used by the Plain Agent prompt.
 * It is review evidence only; the browser remains the authoritative Draw.io renderer.
 */
final class DrawioDraftPngRenderer {

    static final String VERSION = "plain-agent-draft-png-v1";
    private static final int PADDING = 32;
    private static final int MAX_DIMENSION = 4_096;
    private static final int MAX_CELLS = 2_000;

    RenderedDraft render(CanvasAnalysis analysis) {
        List<CanvasCellData> cells = analysis == null || analysis.getCells() == null
                ? List.of()
                : analysis.getCells();
        if (cells.isEmpty() || cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("draft render cell count is invalid");
        }

        Bounds bounds = bounds(cells);
        double scale = Math.min(
                1D,
                Math.min(
                        (MAX_DIMENSION - 2D * PADDING) / Math.max(1D, bounds.width()),
                        (MAX_DIMENSION - 2D * PADDING) / Math.max(1D, bounds.height())));
        int width = dimension(bounds.width() * scale);
        int height = dimension(bounds.height() * scale);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            Transform transform = new Transform(bounds.minX(), bounds.minY(), scale);
            Map<String, CanvasCellData> byId = index(cells);
            drawEdges(graphics, cells, byId, transform);
            drawVertices(graphics, cells, transform);
        } finally {
            graphics.dispose();
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return new RenderedDraft(output.toByteArray(), width, height, VERSION);
        } catch (Exception exception) {
            throw new IllegalArgumentException("draft PNG rendering failed", exception);
        }
    }

    private void drawEdges(
            Graphics2D graphics,
            List<CanvasCellData> cells,
            Map<String, CanvasCellData> byId,
            Transform transform
    ) {
        for (CanvasCellData edge : cells) {
            if (!"edge".equalsIgnoreCase(edge.getKind())) {
                continue;
            }
            List<Point> path = edgePath(edge, byId);
            if (path.size() < 2) {
                continue;
            }
            Map<String, String> style = style(edge.getStyle());
            graphics.setColor(color(style.get("strokeColor"), new Color(0x64748B)));
            float strokeWidth = (float) number(style.get("strokeWidth"), 2D, 1D, 12D);
            float[] dash = "1".equals(style.get("dashed")) ? new float[]{6F, 4F} : null;
            graphics.setStroke(new BasicStroke(
                    strokeWidth,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    10F,
                    dash,
                    0F));
            Path2D line = new Path2D.Double();
            Point first = transform.apply(path.get(0));
            line.moveTo(first.x(), first.y());
            for (int index = 1; index < path.size(); index++) {
                Point point = transform.apply(path.get(index));
                line.lineTo(point.x(), point.y());
            }
            graphics.draw(line);
            if (!"none".equalsIgnoreCase(style.get("endArrow"))) {
                drawArrow(graphics, transform.apply(path.get(path.size() - 2)),
                        transform.apply(path.get(path.size() - 1)));
            }
            drawEdgeLabel(graphics, edge, transform.apply(path.get(path.size() / 2)));
        }
    }

    private void drawVertices(
            Graphics2D graphics,
            List<CanvasCellData> cells,
            Transform transform
    ) {
        List<CanvasCellData> vertices = cells.stream()
                .filter(cell -> !"edge".equalsIgnoreCase(cell.getKind()))
                // Large regions must be painted before their contained nodes.
                .sorted(Comparator.comparingDouble(
                        (CanvasCellData cell) -> cell.getWidth() * cell.getHeight()).reversed())
                .toList();
        for (CanvasCellData cell : vertices) {
            if (cell.getWidth() <= 0D || cell.getHeight() <= 0D) {
                continue;
            }
            Map<String, String> style = style(cell.getStyle());
            double x = transform.x(cell.getX());
            double y = transform.y(cell.getY());
            double width = Math.max(1D, cell.getWidth() * transform.scale());
            double height = Math.max(1D, cell.getHeight() * transform.scale());
            Color fill = "none".equalsIgnoreCase(style.get("fillColor"))
                    ? new Color(255, 255, 255, 0)
                    : color(style.get("fillColor"), new Color(0xF8FAFC));
            Color stroke = "none".equalsIgnoreCase(style.get("strokeColor"))
                    ? new Color(255, 255, 255, 0)
                    : color(style.get("strokeColor"), new Color(0x334155));
            graphics.setStroke(new BasicStroke(
                    (float) number(style.get("strokeWidth"), 2D, 0D, 12D)));

            String shape = StringUtils.defaultString(style.get("shape"));
            if ("ellipse".equals(shape) || style.containsKey("ellipse")) {
                Ellipse2D ellipse = new Ellipse2D.Double(x, y, width, height);
                graphics.setColor(fill);
                graphics.fill(ellipse);
                graphics.setColor(stroke);
                graphics.draw(ellipse);
            } else if ("rhombus".equals(shape) || style.containsKey("rhombus")) {
                Polygon diamond = new Polygon(
                        new int[]{round(x + width / 2D), round(x + width), round(x + width / 2D), round(x)},
                        new int[]{round(y), round(y + height / 2D), round(y + height), round(y + height / 2D)},
                        4);
                graphics.setColor(fill);
                graphics.fillPolygon(diamond);
                graphics.setColor(stroke);
                graphics.drawPolygon(diamond);
            } else {
                double radius = "1".equals(style.get("rounded")) ? 12D : 0D;
                RoundRectangle2D rectangle =
                        new RoundRectangle2D.Double(x, y, width, height, radius, radius);
                graphics.setColor(fill);
                graphics.fill(rectangle);
                graphics.setColor(stroke);
                graphics.draw(rectangle);
            }
            drawNodeLabel(graphics, cell.getLabel(), style, x, y, width, height);
        }
    }

    private void drawNodeLabel(
            Graphics2D graphics,
            String label,
            Map<String, String> style,
            double x,
            double y,
            double width,
            double height
    ) {
        String text = StringUtils.defaultString(label).trim();
        if (text.isBlank()) {
            return;
        }
        int size = round(number(style.get("fontSize"), 14D, 7D, 48D));
        int fontStyle = StringUtils.defaultString(style.get("fontStyle")).contains("1")
                ? Font.BOLD
                : Font.PLAIN;
        graphics.setFont(new Font("SansSerif", fontStyle, size));
        graphics.setColor(color(style.get("fontColor"), new Color(0x0F172A)));
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = wrap(text, metrics, Math.max(24, round(width - 12D)));
        int lineHeight = metrics.getHeight();
        int startY = round(y + Math.max(metrics.getAscent() + 4D,
                (height - lines.size() * lineHeight) / 2D + metrics.getAscent()));
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int textX = round(x + Math.max(4D, (width - metrics.stringWidth(line)) / 2D));
            graphics.drawString(line, textX, startY + index * lineHeight);
        }
    }

    private void drawEdgeLabel(Graphics2D graphics, CanvasCellData edge, Point point) {
        String label = StringUtils.defaultString(edge.getLabel()).trim();
        if (label.isBlank()) {
            return;
        }
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 12));
        FontMetrics metrics = graphics.getFontMetrics();
        int width = metrics.stringWidth(label) + 8;
        int height = metrics.getHeight() + 4;
        graphics.setColor(new Color(255, 255, 255, 225));
        graphics.fillRoundRect(
                round(point.x() - width / 2D),
                round(point.y() - height / 2D),
                width,
                height,
                6,
                6);
        graphics.setColor(new Color(0x334155));
        graphics.drawString(
                label,
                round(point.x() - metrics.stringWidth(label) / 2D),
                round(point.y() + metrics.getAscent() / 2D));
    }

    private void drawArrow(Graphics2D graphics, Point from, Point to) {
        double angle = Math.atan2(to.y() - from.y(), to.x() - from.x());
        int size = 9;
        Polygon arrow = new Polygon();
        arrow.addPoint(round(to.x()), round(to.y()));
        arrow.addPoint(
                round(to.x() - size * Math.cos(angle - Math.PI / 6D)),
                round(to.y() - size * Math.sin(angle - Math.PI / 6D)));
        arrow.addPoint(
                round(to.x() - size * Math.cos(angle + Math.PI / 6D)),
                round(to.y() - size * Math.sin(angle + Math.PI / 6D)));
        graphics.fillPolygon(arrow);
    }

    private List<Point> edgePath(CanvasCellData edge, Map<String, CanvasCellData> byId) {
        ArrayList<Point> path = new ArrayList<>();
        CanvasCellData source = byId.get(edge.getSource());
        CanvasCellData target = byId.get(edge.getTarget());
        Point sourcePoint = point(edge.getSourcePoint());
        Point targetPoint = point(edge.getTargetPoint());
        if (sourcePoint != null) {
            path.add(sourcePoint);
        } else if (source != null) {
            path.add(center(source));
        }
        if (edge.getPoints() != null) {
            edge.getPoints().stream().map(this::point).filter(java.util.Objects::nonNull)
                    .forEach(path::add);
        }
        if (targetPoint != null) {
            path.add(targetPoint);
        } else if (target != null) {
            path.add(center(target));
        }
        return path;
    }

    private Bounds bounds(List<CanvasCellData> cells) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (CanvasCellData cell : cells) {
            if (!"edge".equalsIgnoreCase(cell.getKind()) && cell.getWidth() > 0D && cell.getHeight() > 0D) {
                minX = Math.min(minX, cell.getX());
                minY = Math.min(minY, cell.getY());
                maxX = Math.max(maxX, cell.maxX());
                maxY = Math.max(maxY, cell.maxY());
            }
            List<CanvasPointData> points = cell.getPoints() == null ? List.of() : cell.getPoints();
            for (CanvasPointData point : points) {
                minX = Math.min(minX, point.getX());
                minY = Math.min(minY, point.getY());
                maxX = Math.max(maxX, point.getX());
                maxY = Math.max(maxY, point.getY());
            }
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            return new Bounds(0D, 0D, 640D, 360D);
        }
        return new Bounds(minX, minY, Math.max(1D, maxX - minX), Math.max(1D, maxY - minY));
    }

    private Map<String, CanvasCellData> index(List<CanvasCellData> cells) {
        Map<String, CanvasCellData> result = new HashMap<>();
        for (CanvasCellData cell : cells) {
            result.putIfAbsent(cell.getId(), cell);
        }
        return result;
    }

    private Map<String, String> style(String value) {
        Map<String, String> result = new HashMap<>();
        for (String token : StringUtils.defaultString(value).split(";")) {
            int split = token.indexOf('=');
            if (split > 0) {
                result.put(token.substring(0, split), token.substring(split + 1));
            } else if (!token.isBlank()) {
                result.put(token, "1");
            }
        }
        return result;
    }

    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        String normalized = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (metrics.stringWidth(normalized) <= maxWidth) {
            return List.of(normalized);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : normalized.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
            if (lines.size() == 3) {
                break;
            }
        }
        if (!current.isEmpty() && lines.size() < 4) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of(normalized) : List.copyOf(lines);
    }

    private Color color(String value, Color fallback) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) {
            return fallback;
        }
        try {
            return Color.decode(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double number(String value, double fallback, double minimum, double maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Double.parseDouble(value)));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Point point(CanvasPointData point) {
        return point == null ? null : new Point(point.getX(), point.getY());
    }

    private Point center(CanvasCellData cell) {
        return new Point(cell.centerX(), cell.centerY());
    }

    private int dimension(double extent) {
        return Math.min(MAX_DIMENSION, Math.max(128, round(extent) + 2 * PADDING));
    }

    private int round(double value) {
        return (int) Math.round(value);
    }

    record RenderedDraft(byte[] png, int width, int height, String rendererVersion) {
    }

    private record Bounds(double minX, double minY, double width, double height) {
    }

    private record Point(double x, double y) {
    }

    private record Transform(double minX, double minY, double scale) {
        double x(double value) {
            return (value - minX) * scale + PADDING;
        }

        double y(double value) {
            return (value - minY) * scale + PADDING;
        }

        Point apply(Point point) {
            return new Point(x(point.x()), y(point.y()));
        }
    }
}
