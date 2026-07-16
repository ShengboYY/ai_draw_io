package org.zipp.ai.domain.agent.service.canvas.routing;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.analysis.LayoutFamily;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes local, profile-aware routes for explicitly selected edges.
 *
 * <p>The module owns candidate generation and comparison only. It does not decide which edge needs
 * repair, expose an agent tool, persist XML, or accept the mutation; those responsibilities stay
 * with the validator/reviewer, MCP service, and mutation gate respectively.</p>
 */
public final class TargetedEdgeRouter {

    private static final double GUTTER_CLEARANCE = 40D;
    private static final double LANE_SPACING = 24D;
    private static final double SAFE_PORT_MIN = 0.25D;
    private static final double SAFE_PORT_MAX = 0.75D;
    private static final int MAX_CORRIDORS_PER_AXIS = 12;
    private static final Set<DiagramType> SUPPORTED_PROFILES = Set.of(
            DiagramType.FLOWCHART, DiagramType.ARCHITECTURE, DiagramType.STATE);

    private final DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

    public RoutingResult route(String xml, DiagramType diagramType, Set<String> targetEdgeIds) {
        if (!SUPPORTED_PROFILES.contains(diagramType)) {
            return RoutingResult.noSafe(xml, "Profile " + diagramType + " does not allow generic edge routing.");
        }
        try {
            Document document = DocumentHelper.parseText(xml);
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return RoutingResult.noSafe(xml, "The mxGraphModel root is missing.");
            }

            Graph graph = Graph.read(root);
            LayoutFamily layoutFamily = analyzer.analyze(document.asXML(),
                    diagramType.name().toLowerCase(Locale.ROOT)).getLayoutFamily();
            List<String> changedEdgeIds = new ArrayList<>();
            List<String> unresolvedEdgeIds = new ArrayList<>();
            // Route the farthest return first so inner/outer nested lanes do not force a later
            // return to cross a lane that was allocated too close to the target.
            List<String> orderedEdgeIds = targetEdgeIds.stream()
                    .sorted(Comparator.comparingDouble((String id) -> graph.sourceFlowCoordinate(id, layoutFamily))
                            .reversed().thenComparing(id -> id))
                    .toList();
            for (String edgeId : orderedEdgeIds) {
                Element edge = graph.edges().get(edgeId);
                Node source = graph.nodes().get(edge == null ? null : edge.attributeValue("source"));
                Node target = graph.nodes().get(edge == null ? null : edge.attributeValue("target"));
                if (edge == null || source == null || target == null || source.id().equals(target.id())
                        || isFreeRouted(edge.attributeValue("style"))) {
                    unresolvedEdgeIds.add(edgeId);
                    continue;
                }
                String before = edge.asXML();
                if (routeEdge(document, graph, edge, source, target, diagramType, layoutFamily)) {
                    if (!before.equals(edge.asXML())) {
                        changedEdgeIds.add(edgeId);
                    }
                } else {
                    unresolvedEdgeIds.add(edgeId);
                }
            }
            if (!unresolvedEdgeIds.isEmpty()) {
                // A scoped mutation is atomic. Returning the original XML prevents a caller from
                // applying only the easy subset and leaving the requested repair half-finished.
                return RoutingResult.noSafe(xml, unresolvedEdgeIds,
                        "No safe route candidate for edges " + unresolvedEdgeIds + ".");
            }
            return new RoutingResult(document.asXML(), Status.ROUTED,
                    List.copyOf(changedEdgeIds), List.copyOf(unresolvedEdgeIds), "");
        } catch (Exception error) {
            return RoutingResult.noSafe(xml, "The route candidate could not be computed safely.");
        }
    }

    private boolean routeEdge(Document document,
                              Graph graph,
                              Element edge,
                              Node source,
                              Node target,
                              DiagramType diagramType,
                              LayoutFamily layoutFamily) {
        Element geometry = geometry(edge);
        List<Point> original = waypoints(geometry);
        String originalStyle = edge.attributeValue("style");
        // The analyzer treats TOP_DOWN as vertical flow; LEFT_RIGHT and LAYERED profiles use
        // horizontal flow and therefore route returns through top/bottom gutters.
        boolean topDown = layoutFamily == LayoutFamily.TOP_DOWN;
        boolean returnEdge = resolveRole(edge, source, target, topDown) == EdgeRole.RETURN;

        // A valid explicit route is authoritative. In particular, do not move an outer return edge
        // merely because an unselected inner edge crosses it; repairing that other edge is safer.
        if (!original.isEmpty()
                && preservesIntent(document, edge, original, graph, returnEdge, topDown, diagramType)) {
            return true;
        }

        List<Candidate> candidates = returnEdge
                ? returnCandidates(graph, edge, source, target, topDown)
                : ordinaryCandidates(graph, edge, source, target, original);
        Candidate best = null;
        for (Candidate candidate : candidates) {
            apply(edge, geometry, candidate);
            RouteCost cost = cost(document.asXML(), edge.attributeValue("id"), diagramType,
                    candidate, original, source, target, returnEdge);
            Candidate scored = candidate.withCost(cost);
            if (best == null || scored.cost().compareTo(best.cost()) < 0) {
                best = scored;
            }
        }
        if (best == null || best.cost().nodeCrossings() > 0
                || (returnEdge && best.cost().gutterViolations() > 0)) {
            restore(edge, geometry, original, originalStyle);
            return false;
        }
        apply(edge, geometry, best);
        return true;
    }

    private boolean preservesIntent(Document document,
                                    Element edge,
                                    List<Point> original,
                                    Graph graph,
                                    boolean returnEdge,
                                    boolean topDown,
                                    DiagramType diagramType) {
        if (returnEdge && original.stream().noneMatch(point -> topDown
                ? graph.outsideHorizontalBounds(point.x())
                : graph.outsideVerticalBounds(point.y()))) {
            return false;
        }
        RouteCost cost = cost(document.asXML(), edge.attributeValue("id"), diagramType,
                new Candidate(original, null), original, null, null, returnEdge);
        return cost.nodeCrossings() == 0 && cost.portViolations() == 0;
    }

    private List<Candidate> returnCandidates(Graph graph,
                                             Element edge,
                                             Node source,
                                             Node target,
                                             boolean topDown) {
        List<Candidate> candidates = new ArrayList<>();
        if (topDown) {
            Side preferred = source.distanceToLeftBoundary(graph.minX())
                    <= source.distanceToRightBoundary(graph.maxX()) ? Side.LEFT : Side.RIGHT;
            double distributedTrack = graph.endpointTrack(edge, source.id(), target.id());
            double sourceTrack = portTrack(edge.attributeValue("style"), "exitY", distributedTrack);
            double targetTrack = portTrack(edge.attributeValue("style"), "entryY", distributedTrack);
            for (Side side : List.of(Side.LEFT, Side.RIGHT)) {
                for (int lane = 0; lane < 5; lane++) {
                    double x = side == Side.LEFT
                            ? graph.minX() - GUTTER_CLEARANCE - lane * LANE_SPACING
                            : graph.maxX() + GUTTER_CLEARANCE + lane * LANE_SPACING;
                    candidates.add(new Candidate(List.of(
                            new Point(x, source.trackY(sourceTrack)),
                            new Point(x, target.trackY(targetTrack))),
                            new PortPlan(side, sourceTrack, side, targetTrack, side == preferred ? 0 : 1)));
                }
            }
        } else {
            Side preferred = source.distanceToTopBoundary(graph.minY())
                    <= source.distanceToBottomBoundary(graph.maxY()) ? Side.TOP : Side.BOTTOM;
            double distributedTrack = graph.endpointTrack(edge, source.id(), target.id());
            double sourceTrack = portTrack(edge.attributeValue("style"), "exitX", distributedTrack);
            double targetTrack = portTrack(edge.attributeValue("style"), "entryX", distributedTrack);
            for (Side side : List.of(Side.TOP, Side.BOTTOM)) {
                for (int lane = 0; lane < 5; lane++) {
                    double y = side == Side.TOP
                            ? graph.minY() - GUTTER_CLEARANCE - lane * LANE_SPACING
                            : graph.maxY() + GUTTER_CLEARANCE + lane * LANE_SPACING;
                    candidates.add(new Candidate(List.of(
                            new Point(source.trackX(sourceTrack), y),
                            new Point(target.trackX(targetTrack), y)),
                            new PortPlan(side, sourceTrack, side, targetTrack, side == preferred ? 0 : 1)));
                }
            }
        }
        return candidates;
    }

    private List<Candidate> ordinaryCandidates(Graph graph,
                                               Element edge,
                                               Node source,
                                               Node target,
                                               List<Point> original) {
        List<Candidate> candidates = new ArrayList<>();
        if (!original.isEmpty()) {
            candidates.add(new Candidate(original, null));
        }
        double dx = Math.abs(target.centerX() - source.centerX());
        double dy = Math.abs(target.centerY() - source.centerY());
        double track = graph.endpointTrack(edge, source.id(), target.id());
        if (dx >= dy) {
            Side sourceSide = target.centerX() >= source.centerX() ? Side.RIGHT : Side.LEFT;
            Side targetSide = sourceSide == Side.RIGHT ? Side.LEFT : Side.RIGHT;
            double midX = (source.centerX() + target.centerX()) / 2D;
            candidates.add(new Candidate(List.of(
                    new Point(midX, source.trackY(track)), new Point(midX, target.trackY(track))),
                    new PortPlan(sourceSide, track, targetSide, track, 0)));
            for (double corridorY : graph.horizontalCorridors(source, target)) {
                Side sourcePort = corridorY < source.centerY() ? Side.TOP : Side.BOTTOM;
                Side targetPort = corridorY < target.centerY() ? Side.TOP : Side.BOTTOM;
                candidates.add(new Candidate(List.of(
                        new Point(source.trackX(track), corridorY),
                        new Point(target.trackX(track), corridorY)),
                        new PortPlan(sourcePort, track, targetPort, track,
                                graph.outsideVerticalBounds(corridorY) ? 1 : 0)));
            }
        } else {
            Side sourceSide = target.centerY() >= source.centerY() ? Side.BOTTOM : Side.TOP;
            Side targetSide = sourceSide == Side.BOTTOM ? Side.TOP : Side.BOTTOM;
            double midY = (source.centerY() + target.centerY()) / 2D;
            candidates.add(new Candidate(List.of(
                    new Point(source.trackX(track), midY), new Point(target.trackX(track), midY)),
                    new PortPlan(sourceSide, track, targetSide, track, 0)));
            for (double corridorX : graph.verticalCorridors(source, target)) {
                Side sourcePort = corridorX < source.centerX() ? Side.LEFT : Side.RIGHT;
                Side targetPort = corridorX < target.centerX() ? Side.LEFT : Side.RIGHT;
                candidates.add(new Candidate(List.of(
                        new Point(corridorX, source.trackY(track)),
                        new Point(corridorX, target.trackY(track))),
                        new PortPlan(sourcePort, track, targetPort, track,
                                graph.outsideHorizontalBounds(corridorX) ? 1 : 0)));
            }
        }
        return candidates;
    }

    private RouteCost cost(String candidateXml,
                           String edgeId,
                           DiagramType diagramType,
                           Candidate candidate,
                           List<Point> original,
                           Node source,
                           Node target,
                           boolean returnEdge) {
        CanvasAnalysis analysis = analyzer.analyze(candidateXml, diagramType.name().toLowerCase(Locale.ROOT));
        Map<CanvasIssueType, Integer> counts = new HashMap<>();
        for (CanvasAnalysisIssue issue : analysis.getIssues()) {
            if (issue.getTargetCellIds().contains(edgeId)) {
                counts.merge(issue.getType(), 1, Integer::sum);
            }
        }
        List<Point> route = routePoints(candidate, source, target);
        int bends = bendCount(route);
        int changedWaypoints = changedWaypointCount(original, candidate.points());
        return new RouteCost(
                count(counts, CanvasIssueType.EDGE_NODE_CROSSING),
                count(counts, CanvasIssueType.EDGE_LABEL_COLLISION),
                count(counts, CanvasIssueType.EDGE_EDGE_CROSSING),
                count(counts, CanvasIssueType.EDGE_COLLINEAR_OVERLAP, CanvasIssueType.PARALLEL_EDGE_OVERLAP),
                count(counts, CanvasIssueType.PROTECTED_LANE_INTRUSION),
                returnEdge ? count(counts, CanvasIssueType.RETURN_GUTTER_VIOLATION) : 0,
                candidate.portPlan() == null ? 0 : candidate.portPlan().roleViolation(),
                count(counts, CanvasIssueType.PORT_DIRECTION_MISMATCH,
                        CanvasIssueType.PORT_CORNER_PROXIMITY, CanvasIssueType.NODE_SIDE_PORT_CROWDING),
                bends,
                routeLength(route),
                changedWaypoints);
    }

    private EdgeRole resolveRole(Element edge, Node source, Node target, boolean topDown) {
        String explicit = StringUtils.firstNonBlank(edge.attributeValue("edgeRole"),
                styleTokens(edge.attributeValue("style")).get("edgeRole"));
        EdgeRole explicitRole = EdgeRole.from(explicit);
        if (explicitRole != EdgeRole.UNKNOWN) {
            return explicitRole;
        }

        // Fallback inference is intentionally conservative: it only supplies route selection and
        // is never persisted as authoritative edge metadata.
        String label = StringUtils.defaultString(edge.attributeValue("value")).toLowerCase(Locale.ROOT);
        boolean reverseDirection = topDown
                ? target.centerY() < source.centerY()
                : target.centerX() < source.centerX();
        if (reverseDirection || containsAny(label, "return", "retry", "back", "返回", "重试", "重新")) {
            return EdgeRole.RETURN;
        }
        if (containsAny(label, "error", "failure", "failed", "异常", "错误", "失败")) {
            return EdgeRole.ERROR;
        }
        if (containsAny(label, "async", "callback", "event", "异步", "回调", "事件")) {
            return EdgeRole.ASYNC;
        }
        return EdgeRole.PRIMARY;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private int count(Map<CanvasIssueType, Integer> counts, CanvasIssueType... types) {
        int total = 0;
        for (CanvasIssueType type : types) {
            total += counts.getOrDefault(type, 0);
        }
        return total;
    }

    private double routeLength(List<Point> points) {
        double length = 0D;
        for (int index = 1; index < points.size(); index++) {
            length += Math.abs(points.get(index).x() - points.get(index - 1).x())
                    + Math.abs(points.get(index).y() - points.get(index - 1).y());
        }
        return length;
    }

    private List<Point> routePoints(Candidate candidate, Node source, Node target) {
        if (source == null || target == null) {
            return candidate.points();
        }
        List<Point> points = new ArrayList<>();
        PortPlan plan = candidate.portPlan();
        points.add(plan == null ? source.center() : source.port(plan.sourceSide(), plan.sourceTrack()));
        points.addAll(candidate.points());
        points.add(plan == null ? target.center() : target.port(plan.targetSide(), plan.targetTrack()));
        return points;
    }

    private int bendCount(List<Point> points) {
        int bends = 0;
        for (int index = 1; index + 1 < points.size(); index++) {
            Point before = points.get(index - 1);
            Point current = points.get(index);
            Point after = points.get(index + 1);
            double firstDx = current.x() - before.x();
            double firstDy = current.y() - before.y();
            double secondDx = after.x() - current.x();
            double secondDy = after.y() - current.y();
            if ((Math.abs(firstDx) > 0.0001D || Math.abs(firstDy) > 0.0001D)
                    && (Math.abs(secondDx) > 0.0001D || Math.abs(secondDy) > 0.0001D)
                    && Math.abs(firstDx * secondDy - firstDy * secondDx) > 0.0001D) {
                bends++;
            }
        }
        return bends;
    }

    private int changedWaypointCount(List<Point> original, List<Point> candidate) {
        int changes = Math.abs(original.size() - candidate.size());
        int shared = Math.min(original.size(), candidate.size());
        for (int index = 0; index < shared; index++) {
            if (!original.get(index).equals(candidate.get(index))) {
                changes++;
            }
        }
        return changes;
    }

    private void apply(Element edge, Element geometry, Candidate candidate) {
        if (candidate.portPlan() != null) {
            edge.addAttribute("style", styleWithPorts(edge.attributeValue("style"), candidate.portPlan()));
        }
        replaceWaypoints(geometry, candidate.points());
    }

    private void restore(Element edge, Element geometry, List<Point> original, String originalStyle) {
        if (originalStyle == null) {
            if (edge.attribute("style") != null) {
                edge.remove(edge.attribute("style"));
            }
        } else {
            edge.addAttribute("style", originalStyle);
        }
        replaceWaypoints(geometry, original);
    }

    private String styleWithPorts(String rawStyle, PortPlan plan) {
        Map<String, String> tokens = styleTokens(rawStyle);
        tokens.put("edgeStyle", "orthogonalEdgeStyle");
        tokens.put("exitX", coordinate(plan.sourceSide(), true));
        tokens.put("exitY", coordinate(plan.sourceSide(), plan.sourceTrack(), false));
        tokens.put("entryX", coordinate(plan.targetSide(), true));
        tokens.put("entryY", coordinate(plan.targetSide(), plan.targetTrack(), false));
        return tokens.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";", "", ";"));
    }

    private String coordinate(Side side, boolean xCoordinate) {
        return coordinate(side, 0.5D, xCoordinate);
    }

    private String coordinate(Side side, double track, boolean xCoordinate) {
        if (xCoordinate) {
            return side == Side.LEFT ? "0" : side == Side.RIGHT ? "1" : format(track);
        }
        return side == Side.TOP ? "0" : side == Side.BOTTOM ? "1" : format(track);
    }

    private double portTrack(String style, String token, double fallback) {
        String value = styleTokens(style).get(token);
        double parsed = number(value, fallback);
        return Math.max(SAFE_PORT_MIN, Math.min(SAFE_PORT_MAX, parsed));
    }

    private Map<String, String> styleTokens(String style) {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String token : StringUtils.defaultString(style).split(";")) {
            if (token.isBlank()) {
                continue;
            }
            int equals = token.indexOf('=');
            tokens.put(equals < 0 ? token : token.substring(0, equals),
                    equals < 0 ? "1" : token.substring(equals + 1));
        }
        return tokens;
    }

    private Element geometry(Element edge) {
        Element geometry = edge.element("mxGeometry");
        if (geometry == null) {
            geometry = edge.addElement("mxGeometry");
            geometry.addAttribute("relative", "1");
            geometry.addAttribute("as", "geometry");
        }
        return geometry;
    }

    private List<Point> waypoints(Element geometry) {
        Element array = waypointArray(geometry);
        if (array == null) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (Object item : array.elements("mxPoint")) {
            Element point = (Element) item;
            points.add(new Point(number(point.attributeValue("x"), 0D),
                    number(point.attributeValue("y"), 0D)));
        }
        return List.copyOf(points);
    }

    private void replaceWaypoints(Element geometry, List<Point> points) {
        Element existing = waypointArray(geometry);
        if (existing != null) {
            geometry.remove(existing);
        }
        if (points.isEmpty()) {
            return;
        }
        Element array = geometry.addElement("Array");
        array.addAttribute("as", "points");
        for (Point point : points) {
            Element xmlPoint = array.addElement("mxPoint");
            xmlPoint.addAttribute("x", format(point.x()));
            xmlPoint.addAttribute("y", format(point.y()));
        }
    }

    private Element waypointArray(Element geometry) {
        for (Object item : geometry.elements("Array")) {
            Element array = (Element) item;
            if ("points".equals(array.attributeValue("as"))) {
                return array;
            }
        }
        return null;
    }

    private boolean isFreeRouted(String rawStyle) {
        String style = StringUtils.defaultString(rawStyle).toLowerCase(Locale.ROOT);
        return style.contains("edgestyle=none") || style.contains("curved=1");
    }

    private double number(String raw, double fallback) {
        try {
            return StringUtils.isBlank(raw) ? fallback : Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String format(double value) {
        long rounded = Math.round(value);
        return Math.abs(value - rounded) < 0.0001D
                ? String.valueOf(rounded)
                : StringUtils.stripEnd(StringUtils.stripEnd(
                        String.format(Locale.ROOT, "%.3f", value), "0"), ".");
    }

    public enum Status {
        ROUTED,
        NO_SAFE_CANDIDATE
    }

    public record RoutingResult(String xml,
                                Status status,
                                List<String> changedEdgeIds,
                                List<String> unresolvedEdgeIds,
                                String reason) {
        private static RoutingResult noSafe(String xml, String reason) {
            return noSafe(xml, List.of(), reason);
        }

        private static RoutingResult noSafe(String xml, List<String> unresolvedEdgeIds, String reason) {
            return new RoutingResult(xml, Status.NO_SAFE_CANDIDATE, List.of(),
                    List.copyOf(unresolvedEdgeIds), reason);
        }
    }

    private record Point(double x, double y) {
    }

    private record Candidate(List<Point> points, PortPlan portPlan, RouteCost cost) {
        private Candidate(List<Point> points, PortPlan portPlan) {
            this(List.copyOf(points), portPlan, null);
        }

        private Candidate withCost(RouteCost routeCost) {
            return new Candidate(points, portPlan, routeCost);
        }
    }

    private record PortPlan(Side sourceSide,
                            double sourceTrack,
                            Side targetSide,
                            double targetTrack,
                            int roleViolation) {
    }

    private enum Side {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    private enum EdgeRole {
        PRIMARY,
        BRANCH,
        RETURN,
        ERROR,
        ASYNC,
        RELATION,
        MESSAGE,
        INHERITANCE,
        INCLUDE_EXTEND,
        UNKNOWN;

        private static EdgeRole from(String value) {
            if (StringUtils.isBlank(value)) {
                return UNKNOWN;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    private record RouteCost(int nodeCrossings,
                             int labelCollisions,
                             int edgeCrossings,
                             int collinearOverlaps,
                             int protectedLaneIntrusions,
                             int gutterViolations,
                             int roleViolations,
                             int portViolations,
                             int bends,
                             double length,
                             int changedWaypoints) implements Comparable<RouteCost> {
        @Override
        public int compareTo(RouteCost other) {
            return Comparator.comparingInt(RouteCost::nodeCrossings)
                    .thenComparingInt(RouteCost::labelCollisions)
                    .thenComparingInt(RouteCost::edgeCrossings)
                    .thenComparingInt(RouteCost::collinearOverlaps)
                    .thenComparingInt(RouteCost::protectedLaneIntrusions)
                    .thenComparingInt(RouteCost::gutterViolations)
                    .thenComparingInt(RouteCost::roleViolations)
                    .thenComparingInt(RouteCost::portViolations)
                    .thenComparingInt(RouteCost::bends)
                    .thenComparingDouble(RouteCost::length)
                    .thenComparingInt(RouteCost::changedWaypoints)
                    .compare(this, other);
        }
    }

    private record Node(String id, double x, double y, double width, double height) {
        private double centerX() {
            return x + width / 2D;
        }

        private double centerY() {
            return y + height / 2D;
        }

        private Point center() {
            return new Point(centerX(), centerY());
        }

        private Point port(Side side, double track) {
            return switch (side) {
                case LEFT -> new Point(x, trackY(track));
                case RIGHT -> new Point(maxX(), trackY(track));
                case TOP -> new Point(trackX(track), y);
                case BOTTOM -> new Point(trackX(track), y + height);
            };
        }

        private double trackY(double fraction) {
            return y + height * fraction;
        }

        private double trackX(double fraction) {
            return x + width * fraction;
        }

        private double maxX() {
            return x + width;
        }

        private double distanceToLeftBoundary(double minX) {
            return centerX() - minX;
        }

        private double distanceToRightBoundary(double maxX) {
            return maxX - centerX();
        }

        private double distanceToTopBoundary(double minY) {
            return centerY() - minY;
        }

        private double distanceToBottomBoundary(double maxY) {
            return maxY - centerY();
        }
    }

    private record Graph(Map<String, Node> nodes,
                         Map<String, Element> edges,
                         double minX,
                         double maxX,
                         double minY,
                         double maxY) {
        private static Graph read(Element root) {
            Map<String, RawNode> rawNodes = new LinkedHashMap<>();
            Map<String, Element> edges = new LinkedHashMap<>();
            for (Object item : root.elements("mxCell")) {
                Element cell = (Element) item;
                String id = cell.attributeValue("id");
                if ("1".equals(cell.attributeValue("vertex"))) {
                    Element geometry = cell.element("mxGeometry");
                    if (geometry != null) {
                        rawNodes.put(id, new RawNode(id, cell.attributeValue("parent"),
                                parse(geometry.attributeValue("x")), parse(geometry.attributeValue("y")),
                                parse(geometry.attributeValue("width")), parse(geometry.attributeValue("height")),
                                isBoundary(cell.attributeValue("style"))));
                    }
                } else if ("1".equals(cell.attributeValue("edge"))) {
                    edges.put(id, cell);
                }
            }
            Map<String, Node> nodes = new LinkedHashMap<>();
            for (RawNode raw : rawNodes.values()) {
                Node absolute = raw.absolute(rawNodes, Set.of());
                if (!raw.boundary()) {
                    nodes.put(raw.id(), absolute);
                }
            }
            double minX = nodes.values().stream().mapToDouble(Node::x).min().orElse(0D);
            double maxX = nodes.values().stream().mapToDouble(Node::maxX).max().orElse(0D);
            double minY = nodes.values().stream().mapToDouble(Node::y).min().orElse(0D);
            double maxY = nodes.values().stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0D);
            return new Graph(Map.copyOf(nodes), Map.copyOf(edges), minX, maxX, minY, maxY);
        }

        private boolean outsideHorizontalBounds(double x) {
            return x < minX - 0.0001D || x > maxX + 0.0001D;
        }

        private boolean outsideVerticalBounds(double y) {
            return y < minY - 0.0001D || y > maxY + 0.0001D;
        }

        private double endpointTrack(Element currentEdge, String sourceId, String targetId) {
            List<String> relatedIds = edges.values().stream()
                    .filter(edge -> sameEndpoints(edge, sourceId, targetId))
                    .map(edge -> StringUtils.defaultString(edge.attributeValue("id")))
                    .sorted()
                    .toList();
            if (relatedIds.size() < 2) {
                return 0.5D;
            }
            int index = Math.max(0, relatedIds.indexOf(currentEdge.attributeValue("id")));
            return 0.35D + 0.3D * index / (relatedIds.size() - 1D);
        }

        private boolean sameEndpoints(Element edge, String sourceId, String targetId) {
            return (sourceId.equals(edge.attributeValue("source")) && targetId.equals(edge.attributeValue("target")))
                    || (sourceId.equals(edge.attributeValue("target")) && targetId.equals(edge.attributeValue("source")));
        }

        private double sourceFlowCoordinate(String edgeId, LayoutFamily layoutFamily) {
            Element edge = edges.get(edgeId);
            Node source = nodes.get(edge == null ? null : edge.attributeValue("source"));
            if (source == null) {
                return -Double.MAX_VALUE;
            }
            return layoutFamily == LayoutFamily.TOP_DOWN ? source.centerY() : source.centerX();
        }

        private List<Double> horizontalCorridors(Node source, Node target) {
            List<Double> candidates = new ArrayList<>();
            candidates.add(minY - GUTTER_CLEARANCE);
            candidates.add(maxY + GUTTER_CLEARANCE);
            for (Node node : nodes.values()) {
                if (!node.id().equals(source.id()) && !node.id().equals(target.id())) {
                    candidates.add(node.y() - GUTTER_CLEARANCE);
                    candidates.add(node.y() + node.height() + GUTTER_CLEARANCE);
                }
            }
            return nearestDistinct(candidates, (source.centerY() + target.centerY()) / 2D);
        }

        private List<Double> verticalCorridors(Node source, Node target) {
            List<Double> candidates = new ArrayList<>();
            candidates.add(minX - GUTTER_CLEARANCE);
            candidates.add(maxX + GUTTER_CLEARANCE);
            for (Node node : nodes.values()) {
                if (!node.id().equals(source.id()) && !node.id().equals(target.id())) {
                    candidates.add(node.x() - GUTTER_CLEARANCE);
                    candidates.add(node.maxX() + GUTTER_CLEARANCE);
                }
            }
            return nearestDistinct(candidates, (source.centerX() + target.centerX()) / 2D);
        }

        private List<Double> nearestDistinct(List<Double> candidates, double reference) {
            return candidates.stream()
                    .distinct()
                    .sorted(Comparator.comparingDouble(value -> Math.abs(value - reference)))
                    .limit(MAX_CORRIDORS_PER_AXIS)
                    .toList();
        }

        private static boolean isBoundary(String rawStyle) {
            String style = StringUtils.defaultString(rawStyle).toLowerCase(Locale.ROOT);
            return style.contains("swimlane") || style.contains("container=1") || style.contains("startsize=");
        }

        private static double parse(String value) {
            try {
                return StringUtils.isBlank(value) ? 0D : Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return 0D;
            }
        }
    }

    private record RawNode(String id,
                           String parentId,
                           double x,
                           double y,
                           double width,
                           double height,
                           boolean boundary) {
        private Node absolute(Map<String, RawNode> nodes, Set<String> ancestors) {
            if (ancestors.contains(id)) {
                return new Node(id, x, y, width, height);
            }
            RawNode parent = nodes.get(parentId);
            if (parent == null) {
                return new Node(id, x, y, width, height);
            }
            java.util.HashSet<String> nextAncestors = new java.util.HashSet<>(ancestors);
            nextAncestors.add(id);
            Node absoluteParent = parent.absolute(nodes, Set.copyOf(nextAncestors));
            return new Node(id, absoluteParent.x() + x, absoluteParent.y() + y, width, height);
        }
    }
}
