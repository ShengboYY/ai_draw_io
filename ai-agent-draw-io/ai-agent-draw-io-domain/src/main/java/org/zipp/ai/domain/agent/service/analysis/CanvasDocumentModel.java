package org.zipp.ai.domain.agent.service.analysis;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasPointData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Internal canonical view of one Draw.io document. It owns XML parsing, absolute-coordinate
 * normalization and edge-path reconstruction so quality rules consume one shared geometry model.
 */
final class CanvasDocumentModel {

    private final boolean rootPresent;
    private final List<CanvasCellData> cells;
    private final Map<String, CanvasCellData> firstCellById;
    private final Map<CanvasCellData, EdgePath> paths;
    private final Map<CanvasCellData, EdgePorts> ports;

    private CanvasDocumentModel(boolean rootPresent, List<CanvasCellData> cells) {
        this.rootPresent = rootPresent;
        this.cells = List.copyOf(cells);
        this.firstCellById = indexFirstCells(cells);
        normalizeAbsoluteCoordinates(cells, firstCellById);
        this.ports = buildPorts(cells);
        this.paths = buildPaths(cells, firstCellById, ports);
    }

    static CanvasDocumentModel parse(String xml) throws Exception {
        Document document = DocumentHelper.parseText(toGraphModel(xml));
        Element root = document.getRootElement().element("root");
        return new CanvasDocumentModel(root != null, root == null ? List.of() : readCells(root));
    }

    boolean rootPresent() {
        return rootPresent;
    }

    List<CanvasCellData> cells() {
        return cells;
    }

    CanvasCellData cell(String id) {
        return firstCellById.get(id);
    }

    EdgePath path(CanvasCellData edge) {
        return paths.getOrDefault(edge, EdgePath.unavailable());
    }

    EdgePorts ports(CanvasCellData edge) {
        return ports.get(edge);
    }

    CanvasCellData parent(CanvasCellData cell) {
        return cell == null ? null : firstCellById.get(cell.getParentId());
    }

    boolean relatedByAncestry(CanvasCellData left, CanvasCellData right) {
        return isAncestorOf(left, right) || isAncestorOf(right, left);
    }

    CanvasBounds bounds(CanvasCellData cell) {
        return new CanvasBounds(cell.getX(), cell.getY(), cell.getWidth(), cell.getHeight());
    }

    CanvasBounds edgeLabelBounds(CanvasCellData edge, double width, double height) {
        CanvasPointData midpoint = midpointByLength(path(edge).points());
        return new CanvasBounds(midpoint.getX() - width / 2D, midpoint.getY() - height / 2D,
                width, height);
    }

    List<PathSegment> segments(CanvasCellData edge) {
        List<CanvasPointData> points = path(edge).points();
        List<PathSegment> segments = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            segments.add(new PathSegment(i, points.get(i), points.get(i + 1)));
        }
        return List.copyOf(segments);
    }

    List<CanvasPointData> directPath(CanvasCellData edge) {
        CanvasCellData source = cell(edge.getSource());
        CanvasCellData target = cell(edge.getTarget());
        if (source == null || target == null) {
            return List.of();
        }
        return List.of(
                anchorToward(source, edge.getSourcePoint(), center(target)),
                anchorToward(target, edge.getTargetPoint(), center(source))
        );
    }

    private static Map<String, CanvasCellData> indexFirstCells(List<CanvasCellData> cells) {
        Map<String, CanvasCellData> result = new HashMap<>();
        for (CanvasCellData cell : cells) {
            result.putIfAbsent(cell.getId(), cell);
        }
        return Map.copyOf(result);
    }

    private static Map<CanvasCellData, EdgePath> buildPaths(List<CanvasCellData> cells,
                                                             Map<String, CanvasCellData> cellsById,
                                                             Map<CanvasCellData, EdgePorts> ports) {
        Map<CanvasCellData, EdgePath> result = new IdentityHashMap<>();
        for (CanvasCellData edge : cells) {
            if ("edge".equals(edge.getKind())) {
                result.put(edge, reconstructPath(edge, cellsById, ports.get(edge)));
            }
        }
        return result;
    }

    private static Map<CanvasCellData, EdgePorts> buildPorts(List<CanvasCellData> cells) {
        Map<CanvasCellData, EdgePorts> result = new IdentityHashMap<>();
        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind())) {
                continue;
            }
            result.put(edge, EdgePorts.fromStyle(edge.getStyle()));
        }
        return result;
    }

    private static EdgePath reconstructPath(CanvasCellData edge,
                                            Map<String, CanvasCellData> cellsById,
                                            EdgePorts ports) {
        CanvasCellData source = cellsById.get(edge.getSource());
        CanvasCellData target = cellsById.get(edge.getTarget());
        boolean curved = curvedEdge(edge);

        if (edge.getPoints() != null && !edge.getPoints().isEmpty()) {
            List<CanvasPointData> route = edgeRoute(edge, source, target);
            return new EdgePath(route, curved ? PathConfidence.LOW : PathConfidence.HIGH,
                    curved ? "FREEFORM_CURVE" : null);
        }
        if (source == null && target == null && edge.getSourcePoint() != null && edge.getTargetPoint() != null) {
            return new EdgePath(List.of(edge.getSourcePoint(), edge.getTargetPoint()),
                    curved ? PathConfidence.LOW : PathConfidence.HIGH,
                    curved ? "FREEFORM_CURVE" : null);
        }
        if (source == null || target == null) {
            return EdgePath.unavailable();
        }
        if (curved) {
            return new EdgePath(edgeRoute(edge, source, target), PathConfidence.LOW, "FREEFORM_CURVE");
        }

        List<CanvasPointData> portRoute = portBasedRoute(edge, source, target, ports);
        if (portRoute != null) {
            return new EdgePath(portRoute, PathConfidence.MEDIUM, null);
        }
        List<CanvasPointData> estimated = edgeRoute(edge, source, target);
        if (isAutoRoutedWithoutWaypoints(edge)) {
            return new EdgePath(estimated, PathConfidence.LOW,
                    "AUTO_ROUTE_WITHOUT_WAYPOINTS");
        }
        // A plain/edgeStyle=none connector renders as a straight segment. Its perimeter
        // anchors are estimated, so it is medium-confidence rather than an auto-route limitation.
        return new EdgePath(estimated, PathConfidence.MEDIUM, null);
    }

    private static List<CanvasCellData> readCells(Element root) {
        List<CanvasCellData> cells = new ArrayList<>();
        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            String id = StringUtils.defaultString(cell.attributeValue("id"));
            if ("0".equals(id) || "1".equals(id)) {
                continue;
            }
            Element geometry = cell.element("mxGeometry");
            cells.add(CanvasCellData.builder()
                    .id(id)
                    .label(cleanLabel(cell.attributeValue("value")))
                    .kind(resolveKind(cell))
                    .style(StringUtils.defaultString(cell.attributeValue("style")))
                    .parentId(StringUtils.defaultString(cell.attributeValue("parent")))
                    .source(StringUtils.defaultString(cell.attributeValue("source")))
                    .target(StringUtils.defaultString(cell.attributeValue("target")))
                    .sourcePoint(readNamedPoint(geometry, "sourcePoint"))
                    .targetPoint(readNamedPoint(geometry, "targetPoint"))
                    .points(readWaypoints(geometry))
                    .x(number(geometry, "x"))
                    .y(number(geometry, "y"))
                    .width(number(geometry, "width"))
                    .height(number(geometry, "height"))
                    .rawXml(cell.asXML())
                    .build());
        }
        return cells;
    }

    private static CanvasPointData readNamedPoint(Element geometry, String name) {
        if (geometry == null) {
            return null;
        }
        for (Object item : geometry.elements("mxPoint")) {
            Element point = (Element) item;
            if (StringUtils.equals(name, point.attributeValue("as"))) {
                return point(point);
            }
        }
        return null;
    }

    private static List<CanvasPointData> readWaypoints(Element geometry) {
        List<CanvasPointData> points = new ArrayList<>();
        if (geometry == null) {
            return points;
        }
        for (Object item : geometry.elements("Array")) {
            Element array = (Element) item;
            if (!StringUtils.equals("points", array.attributeValue("as"))) {
                continue;
            }
            for (Object pointItem : array.elements("mxPoint")) {
                points.add(point((Element) pointItem));
            }
        }
        return points;
    }

    private static CanvasPointData point(Element point) {
        return CanvasPointData.builder()
                .x(number(point, "x"))
                .y(number(point, "y"))
                .build();
    }

    private static void normalizeAbsoluteCoordinates(List<CanvasCellData> cells,
                                                     Map<String, CanvasCellData> cellsById) {
        Set<String> resolved = new HashSet<>();
        for (CanvasCellData cell : cells) {
            resolvePosition(cell, cellsById, resolved, new HashSet<>());
        }
        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind())) {
                continue;
            }
            CanvasCellData parent = cellsById.get(edge.getParentId());
            if (parent == null || !"node".equals(parent.getKind())) {
                continue;
            }
            // Edge control points are local to their containing group/swimlane.
            edge.setSourcePoint(translate(edge.getSourcePoint(), parent.getX(), parent.getY()));
            edge.setTargetPoint(translate(edge.getTargetPoint(), parent.getX(), parent.getY()));
            edge.setPoints(edge.getPoints().stream()
                    .map(point -> translate(point, parent.getX(), parent.getY()))
                    .toList());
        }
    }

    private static void resolvePosition(CanvasCellData cell,
                                        Map<String, CanvasCellData> cellsById,
                                        Set<String> resolved,
                                        Set<String> resolving) {
        if (cell == null || resolved.contains(cell.getId()) || resolving.contains(cell.getId())) {
            return;
        }
        resolving.add(cell.getId());
        CanvasCellData parent = cellsById.get(cell.getParentId());
        if (parent != null && "node".equals(parent.getKind())) {
            resolvePosition(parent, cellsById, resolved, resolving);
            cell.setX(parent.getX() + cell.getX());
            cell.setY(parent.getY() + cell.getY());
        }
        resolving.remove(cell.getId());
        resolved.add(cell.getId());
    }

    private static CanvasPointData translate(CanvasPointData point, double dx, double dy) {
        if (point == null) {
            return null;
        }
        return CanvasPointData.builder().x(point.getX() + dx).y(point.getY() + dy).build();
    }

    private static List<CanvasPointData> edgeRoute(CanvasCellData edge,
                                                   CanvasCellData source,
                                                   CanvasCellData target) {
        List<CanvasPointData> waypoints = edge.getPoints() == null ? List.of() : edge.getPoints();
        List<CanvasPointData> route = new ArrayList<>();
        CanvasPointData firstDirection = waypoints.isEmpty()
                ? (target == null ? edge.getTargetPoint() : center(target))
                : waypoints.get(0);
        CanvasPointData lastDirection = waypoints.isEmpty()
                ? (source == null ? edge.getSourcePoint() : center(source))
                : waypoints.get(waypoints.size() - 1);
        CanvasPointData start = source == null
                ? edge.getSourcePoint()
                : edgeEndpoint(edge, source, true, firstDirection);
        CanvasPointData end = target == null
                ? edge.getTargetPoint()
                : edgeEndpoint(edge, target, false, lastDirection);
        if (start != null) {
            route.add(start);
        }
        route.addAll(waypoints);
        if (end != null) {
            route.add(end);
        }
        return List.copyOf(route);
    }

    private static List<CanvasPointData> portBasedRoute(CanvasCellData edge,
                                                        CanvasCellData source,
                                                        CanvasCellData target,
                                                        EdgePorts ports) {
        String style = StringUtils.defaultString(edge.getStyle());
        if (!ports.complete()) {
            return null;
        }
        double exitX = ports.exitX();
        double exitY = ports.exitY();
        double entryX = ports.entryX();
        double entryY = ports.entryY();
        CanvasPointData exitPoint = CanvasPointData.builder()
                .x(source.getX() + exitX * source.getWidth())
                .y(source.getY() + exitY * source.getHeight())
                .build();
        CanvasPointData entryPoint = CanvasPointData.builder()
                .x(target.getX() + entryX * target.getWidth())
                .y(target.getY() + entryY * target.getHeight())
                .build();

        List<CanvasPointData> route = new ArrayList<>();
        route.add(exitPoint);
        String lower = style.toLowerCase(Locale.ROOT);
        boolean orthogonal = lower.contains("orthogonaledgestyle") || lower.contains("elbowedgestyle");
        if (orthogonal) {
            boolean exitHorizontal = exitX == 0D || exitX == 1D || (exitY != 0D && exitY != 1D);
            boolean entryHorizontal = entryX == 0D || entryX == 1D || (entryY != 0D && entryY != 1D);
            if (exitHorizontal && entryHorizontal) {
                double midX = (exitPoint.getX() + entryPoint.getX()) / 2D;
                route.add(point(midX, exitPoint.getY()));
                route.add(point(midX, entryPoint.getY()));
            } else if (!exitHorizontal && !entryHorizontal) {
                double midY = (exitPoint.getY() + entryPoint.getY()) / 2D;
                route.add(point(exitPoint.getX(), midY));
                route.add(point(entryPoint.getX(), midY));
            } else if (exitHorizontal) {
                route.add(point(entryPoint.getX(), exitPoint.getY()));
            } else {
                route.add(point(exitPoint.getX(), entryPoint.getY()));
            }
        }
        route.add(entryPoint);
        return List.copyOf(route);
    }

    private static CanvasPointData edgeEndpoint(CanvasCellData edge,
                                                CanvasCellData node,
                                                boolean sourceEndpoint,
                                                CanvasPointData fallbackDirection) {
        CanvasPointData explicitPoint = sourceEndpoint ? edge.getSourcePoint() : edge.getTargetPoint();
        if (explicitPoint != null) {
            return explicitPoint;
        }
        String style = StringUtils.defaultString(edge.getStyle());
        Double xFraction = styleFraction(style, sourceEndpoint ? "exitX" : "entryX");
        Double yFraction = styleFraction(style, sourceEndpoint ? "exitY" : "entryY");
        if (xFraction != null && yFraction != null) {
            return point(node.getX() + xFraction * node.getWidth(),
                    node.getY() + yFraction * node.getHeight());
        }
        return anchorToward(node, null, fallbackDirection);
    }

    private static CanvasPointData anchorToward(CanvasCellData node,
                                                CanvasPointData explicitPoint,
                                                CanvasPointData direction) {
        if (explicitPoint != null) {
            return explicitPoint;
        }
        if (direction == null) {
            return center(node);
        }
        double dx = direction.getX() - node.centerX();
        double dy = direction.getY() - node.centerY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            return point(dx >= 0D ? node.maxX() : node.getX(), node.centerY());
        }
        return point(node.centerX(), dy >= 0D ? node.maxY() : node.getY());
    }

    private static CanvasPointData center(CanvasCellData node) {
        return point(node.centerX(), node.centerY());
    }

    private boolean isAncestorOf(CanvasCellData ancestor, CanvasCellData descendant) {
        CanvasCellData current = parent(descendant);
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (StringUtils.equals(current.getId(), ancestor.getId())) {
                return true;
            }
            current = parent(current);
        }
        return false;
    }

    private static CanvasPointData midpointByLength(List<CanvasPointData> route) {
        if (route.isEmpty()) {
            return point(0D, 0D);
        }
        double total = 0D;
        for (int i = 0; i + 1 < route.size(); i++) {
            total += distance(route.get(i), route.get(i + 1));
        }
        double target = total / 2D;
        double walked = 0D;
        for (int i = 0; i + 1 < route.size(); i++) {
            CanvasPointData start = route.get(i);
            CanvasPointData end = route.get(i + 1);
            double segment = distance(start, end);
            if (walked + segment >= target && segment > 0D) {
                double ratio = (target - walked) / segment;
                return point(start.getX() + ratio * (end.getX() - start.getX()),
                        start.getY() + ratio * (end.getY() - start.getY()));
            }
            walked += segment;
        }
        return route.get(route.size() - 1);
    }

    private static double distance(CanvasPointData left, CanvasPointData right) {
        return Math.hypot(right.getX() - left.getX(), right.getY() - left.getY());
    }

    private static CanvasPointData point(double x, double y) {
        return CanvasPointData.builder().x(x).y(y).build();
    }

    private static boolean curvedEdge(CanvasCellData edge) {
        return StringUtils.defaultString(edge.getStyle()).toLowerCase(Locale.ROOT).contains("curved=1");
    }

    private static boolean isAutoRoutedWithoutWaypoints(CanvasCellData edge) {
        String style = StringUtils.defaultString(edge.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("orthogonaledgestyle")
                || style.contains("elbowedgestyle")
                || style.contains("entityrelationedgestyle")
                || style.contains("isometricedgestyle");
    }

    private static Double styleFraction(String style, String token) {
        int index = style.indexOf(token + "=");
        if (index < 0) {
            return null;
        }
        int start = index + token.length() + 1;
        int end = start;
        while (end < style.length() && (Character.isDigit(style.charAt(end)) || style.charAt(end) == '.')) {
            end++;
        }
        try {
            return Double.parseDouble(style.substring(start, end));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String toGraphModel(String xml) {
        String normalized = normalizeXml(xml);
        String graphModel = extractGraphModel(normalized);
        if (StringUtils.isNotBlank(graphModel)) {
            return graphModel;
        }
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + normalized
                + "</root></mxGraphModel>";
    }

    private static String normalizeXml(String xml) {
        return StringUtils.trimToEmpty(xml)
                .replace("```xml", "")
                .replace("```", "")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\/", "/")
                .trim();
    }

    private static String extractGraphModel(String xml) {
        int xmlStart = xml.indexOf("<mxGraphModel");
        int xmlEnd = xml.lastIndexOf("</mxGraphModel>");
        if (xmlStart < 0 || xmlEnd < xmlStart) {
            return "";
        }
        return xml.substring(xmlStart, xmlEnd + "</mxGraphModel>".length());
    }

    private static String cleanLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("\\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String resolveKind(Element cell) {
        if ("1".equals(cell.attributeValue("vertex"))) {
            return "node";
        }
        if ("1".equals(cell.attributeValue("edge"))) {
            return "edge";
        }
        return "cell";
    }

    private static double number(Element element, String attribute) {
        if (element == null) {
            return 0D;
        }
        String raw = element.attributeValue(attribute);
        if (StringUtils.isBlank(raw)) {
            return 0D;
        }
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return 0D;
        }
    }

    enum PathConfidence {
        HIGH(1D),
        MEDIUM(0.75D),
        LOW(0.35D),
        UNAVAILABLE(0.1D);

        private final double score;

        PathConfidence(double score) {
            this.score = score;
        }

        double score() {
            return score;
        }

        boolean supportsPreciseGeometry() {
            return this == HIGH || this == MEDIUM;
        }
    }

    record EdgePath(List<CanvasPointData> points,
                    PathConfidence confidence,
                    String limitation) {
        EdgePath {
            points = points == null ? List.of() : List.copyOf(points);
        }

        static EdgePath unavailable() {
            return new EdgePath(List.of(), PathConfidence.UNAVAILABLE, "ROUTE_UNAVAILABLE");
        }
    }

    record PathSegment(int index, CanvasPointData start, CanvasPointData end) {
    }

    record EdgePorts(Double exitX, Double exitY, Double entryX, Double entryY) {
        static EdgePorts fromStyle(String style) {
            String normalized = StringUtils.defaultString(style);
            return new EdgePorts(
                    styleFraction(normalized, "exitX"),
                    styleFraction(normalized, "exitY"),
                    styleFraction(normalized, "entryX"),
                    styleFraction(normalized, "entryY"));
        }

        boolean complete() {
            return exitX != null && exitY != null && entryX != null && entryY != null;
        }
    }

    record CanvasBounds(double x, double y, double width, double height) {
        boolean overlaps(CanvasBounds other) {
            return x < other.x + other.width
                    && x + width > other.x
                    && y < other.y + other.height
                    && y + height > other.y;
        }
    }
}
