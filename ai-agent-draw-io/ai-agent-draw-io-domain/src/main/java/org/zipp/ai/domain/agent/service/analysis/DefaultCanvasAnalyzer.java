package org.zipp.ai.domain.agent.service.analysis;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasPointData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DefaultCanvasAnalyzer implements ICanvasAnalyzer {

    private static final double OVERLAP_TOLERANCE = 8D;
    private static final double GEOMETRY_EPSILON = 0.0001D;

    @Override
    public CanvasAnalysis analyze(String mxGraphModelXml, String diagramType) {
        if (StringUtils.isBlank(mxGraphModelXml)) {
            return invalid("No Draw.io XML was provided.");
        }

        try {
            Document document = DocumentHelper.parseText(toGraphModel(mxGraphModelXml));
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return invalid("mxGraphModel is missing a root element.");
            }

            List<CanvasCellData> cells = readCells(root);
            normalizeAbsoluteCoordinates(cells);
            List<CanvasAnalysisIssue> issues = analyzeIssues(cells);
            return CanvasAnalysis.builder()
                    .valid(issues.isEmpty())
                    .severity(resolveSeverity(issues))
                    .issues(issues)
                    .cells(cells)
                    .summary(summary(cells))
                    .build();
        } catch (Exception e) {
            return invalid("The Draw.io XML could not be parsed: " + e.getMessage());
        }
    }

    private CanvasAnalysis invalid(String message) {
        return CanvasAnalysis.invalid("critical", issue(
                CanvasIssueType.INVALID_XML,
                "structure",
                "critical",
                List.of(),
                message,
                "none"
        ));
    }

    private List<CanvasCellData> readCells(Element root) {
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

    private CanvasPointData readNamedPoint(Element geometry, String name) {
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

    private List<CanvasPointData> readWaypoints(Element geometry) {
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

    private CanvasPointData point(Element point) {
        return CanvasPointData.builder()
                .x(number(point, "x"))
                .y(number(point, "y"))
                .build();
    }

    private void normalizeAbsoluteCoordinates(List<CanvasCellData> cells) {
        // Child mxGeometry values are local to their parent; geometry checks need absolute bounds.
        Map<String, CanvasCellData> firstCellById = new HashMap<>();
        for (CanvasCellData cell : cells) {
            firstCellById.putIfAbsent(cell.getId(), cell);
        }

        Set<String> resolved = new HashSet<>();
        for (CanvasCellData cell : cells) {
            resolvePosition(cell, firstCellById, resolved, new HashSet<>());
        }
    }

    private void resolvePosition(CanvasCellData cell,
                                 Map<String, CanvasCellData> cellById,
                                 Set<String> resolved,
                                 Set<String> resolving) {
        if (cell == null || resolved.contains(cell.getId()) || resolving.contains(cell.getId())) {
            return;
        }
        resolving.add(cell.getId());
        CanvasCellData parent = cellById.get(cell.getParentId());
        if (parent != null && "node".equals(parent.getKind())) {
            resolvePosition(parent, cellById, resolved, resolving);
            cell.setX(parent.getX() + cell.getX());
            cell.setY(parent.getY() + cell.getY());
        }
        resolving.remove(cell.getId());
        resolved.add(cell.getId());
    }

    private List<CanvasAnalysisIssue> analyzeIssues(List<CanvasCellData> cells) {
        List<CanvasAnalysisIssue> issues = new ArrayList<>();
        if (cells.isEmpty()) {
            issues.add(issue(CanvasIssueType.INVALID_XML, "structure", "critical", List.of(),
                    "Diagram has no drawable cells.", "none"));
            return issues;
        }

        validateCells(cells, issues);
        detectNodeOverlaps(cells, issues);
        detectEdgeNodeCrossings(cells, issues);
        detectOpaqueTextBackgrounds(cells, issues);
        return issues;
    }

    private void validateCells(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Set<String> seen = new HashSet<>();
        Set<String> ids = cells.stream()
                .map(CanvasCellData::getId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        for (CanvasCellData cell : cells) {
            if (StringUtils.isBlank(cell.getId())) {
                issues.add(issue(CanvasIssueType.MISSING_GEOMETRY, "structure", "critical", List.of(),
                        "A cell is missing id.", "none"));
            } else if (!seen.add(cell.getId())) {
                issues.add(issue(CanvasIssueType.DUP_ID, "structure", "critical", List.of(cell.getId()),
                        "Duplicate cell id: " + cell.getId(), "none"));
            }

            if ("node".equals(cell.getKind()) && (cell.getWidth() <= 0 || cell.getHeight() <= 0)) {
                issues.add(issue(CanvasIssueType.MISSING_GEOMETRY, "structure", "critical", List.of(cell.getId()),
                        "Vertex is missing usable geometry: " + cell.getId(), "none"));
            }

            if ("edge".equals(cell.getKind())) {
                validateEdge(cell, ids, issues);
            }
        }
    }

    private void validateEdge(CanvasCellData edge, Set<String> ids, List<CanvasAnalysisIssue> issues) {
        if (StringUtils.isNotBlank(edge.getSource()) && !ids.contains(edge.getSource())) {
            issues.add(issue(CanvasIssueType.BROKEN_EDGE, "structure", "critical", List.of(edge.getId(), edge.getSource()),
                    "Edge " + edge.getId() + " source id does not exist: " + edge.getSource(), "none"));
        }
        if (StringUtils.isNotBlank(edge.getTarget()) && !ids.contains(edge.getTarget())) {
            issues.add(issue(CanvasIssueType.BROKEN_EDGE, "structure", "critical", List.of(edge.getId(), edge.getTarget()),
                    "Edge " + edge.getId() + " target id does not exist: " + edge.getTarget(), "none"));
        }
        if (StringUtils.isBlank(edge.getSource()) && StringUtils.isBlank(edge.getTarget())) {
            issues.add(issue(CanvasIssueType.BROKEN_EDGE, "structure", "critical", List.of(edge.getId()),
                    "Edge " + edge.getId() + " has no source/target ids.", "none"));
        }
    }

    private void detectNodeOverlaps(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        List<CanvasCellData> nodes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell))
                .toList();

        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                CanvasCellData left = nodes.get(i);
                CanvasCellData right = nodes.get(j);
                double width = Math.min(left.maxX(), right.maxX()) - Math.max(left.getX(), right.getX());
                double height = Math.min(left.maxY(), right.maxY()) - Math.max(left.getY(), right.getY());
                if (width > OVERLAP_TOLERANCE && height > OVERLAP_TOLERANCE && !isLikelyParentChild(left, right)) {
                    issues.add(issue(CanvasIssueType.NODE_OVERLAP, "geometry", "major", List.of(left.getId(), right.getId()),
                            "Overlapping nodes: " + left.getId() + " and " + right.getId(), "candidate"));
                }
            }
        }
    }

    private void detectEdgeNodeCrossings(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        List<CanvasCellData> nodes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell))
                .toList();

        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind())) {
                continue;
            }
            CanvasCellData source = cellsById.get(edge.getSource());
            CanvasCellData target = cellsById.get(edge.getTarget());
            if (source == null || target == null) {
                continue;
            }
            List<CanvasPointData> route = edgeRoute(edge, source, target);
            for (CanvasCellData node : nodes) {
                if (isCrossingEndpointOrContainer(edge, node) || !routeIntersectsNode(route, node)) {
                    continue;
                }
                issues.add(issue(CanvasIssueType.EDGE_NODE_CROSSING, "geometry", "major", List.of(edge.getId(), node.getId()),
                        "Edge " + edge.getId() + " crosses node body: " + node.getId(), "auto_reroute"));
            }
        }
    }

    private boolean isCrossingEndpointOrContainer(CanvasCellData edge, CanvasCellData node) {
        return StringUtils.equals(node.getId(), edge.getSource())
                || StringUtils.equals(node.getId(), edge.getTarget())
                || StringUtils.equals(node.getId(), edge.getParentId());
    }

    private List<CanvasPointData> edgeRoute(CanvasCellData edge, CanvasCellData source, CanvasCellData target) {
        List<CanvasPointData> waypoints = edge.getPoints() == null ? List.of() : edge.getPoints();
        List<CanvasPointData> route = new ArrayList<>();
        CanvasPointData firstDirection = waypoints.isEmpty() ? center(target) : waypoints.get(0);
        CanvasPointData lastDirection = waypoints.isEmpty() ? center(source) : waypoints.get(waypoints.size() - 1);
        route.add(anchorToward(source, edge.getSourcePoint(), firstDirection));
        route.addAll(waypoints);
        route.add(anchorToward(target, edge.getTargetPoint(), lastDirection));
        return route;
    }

    private CanvasPointData anchorToward(CanvasCellData node, CanvasPointData explicitPoint, CanvasPointData direction) {
        if (explicitPoint != null) {
            return explicitPoint;
        }
        double dx = direction.getX() - node.centerX();
        double dy = direction.getY() - node.centerY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            return CanvasPointData.builder()
                    .x(dx >= 0D ? node.maxX() : node.getX())
                    .y(node.centerY())
                    .build();
        }
        return CanvasPointData.builder()
                .x(node.centerX())
                .y(dy >= 0D ? node.maxY() : node.getY())
                .build();
    }

    private CanvasPointData center(CanvasCellData node) {
        return CanvasPointData.builder()
                .x(node.centerX())
                .y(node.centerY())
                .build();
    }

    private boolean routeIntersectsNode(List<CanvasPointData> route, CanvasCellData node) {
        for (int i = 0; i + 1 < route.size(); i++) {
            if (segmentIntersectsRect(route.get(i), route.get(i + 1), node)) {
                return true;
            }
        }
        return false;
    }

    private boolean segmentIntersectsRect(CanvasPointData start, CanvasPointData end, CanvasCellData rect) {
        if (pointInsideRect(start, rect) || pointInsideRect(end, rect)) {
            return true;
        }
        CanvasPointData topLeft = CanvasPointData.builder().x(rect.getX()).y(rect.getY()).build();
        CanvasPointData topRight = CanvasPointData.builder().x(rect.maxX()).y(rect.getY()).build();
        CanvasPointData bottomRight = CanvasPointData.builder().x(rect.maxX()).y(rect.maxY()).build();
        CanvasPointData bottomLeft = CanvasPointData.builder().x(rect.getX()).y(rect.maxY()).build();
        return segmentsIntersect(start, end, topLeft, topRight)
                || segmentsIntersect(start, end, topRight, bottomRight)
                || segmentsIntersect(start, end, bottomRight, bottomLeft)
                || segmentsIntersect(start, end, bottomLeft, topLeft);
    }

    private boolean pointInsideRect(CanvasPointData point, CanvasCellData rect) {
        return point.getX() > rect.getX() + GEOMETRY_EPSILON
                && point.getX() < rect.maxX() - GEOMETRY_EPSILON
                && point.getY() > rect.getY() + GEOMETRY_EPSILON
                && point.getY() < rect.maxY() - GEOMETRY_EPSILON;
    }

    private boolean segmentsIntersect(CanvasPointData a, CanvasPointData b, CanvasPointData c, CanvasPointData d) {
        double first = orientation(a, b, c);
        double second = orientation(a, b, d);
        double third = orientation(c, d, a);
        double fourth = orientation(c, d, b);
        if (oppositeSigns(first, second) && oppositeSigns(third, fourth)) {
            return true;
        }
        return onSegment(a, c, b, first)
                || onSegment(a, d, b, second)
                || onSegment(c, a, d, third)
                || onSegment(c, b, d, fourth);
    }

    private boolean oppositeSigns(double left, double right) {
        return (left > GEOMETRY_EPSILON && right < -GEOMETRY_EPSILON)
                || (left < -GEOMETRY_EPSILON && right > GEOMETRY_EPSILON);
    }

    private double orientation(CanvasPointData a, CanvasPointData b, CanvasPointData point) {
        return (b.getX() - a.getX()) * (point.getY() - a.getY())
                - (b.getY() - a.getY()) * (point.getX() - a.getX());
    }

    private boolean onSegment(CanvasPointData start, CanvasPointData point, CanvasPointData end, double orientation) {
        if (Math.abs(orientation) > GEOMETRY_EPSILON) {
            return false;
        }
        return point.getX() >= Math.min(start.getX(), end.getX()) - GEOMETRY_EPSILON
                && point.getX() <= Math.max(start.getX(), end.getX()) + GEOMETRY_EPSILON
                && point.getY() >= Math.min(start.getY(), end.getY()) - GEOMETRY_EPSILON
                && point.getY() <= Math.max(start.getY(), end.getY()) + GEOMETRY_EPSILON;
    }

    private void detectOpaqueTextBackgrounds(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        for (CanvasCellData cell : cells) {
            if ("node".equals(cell.getKind()) && isTextCell(cell) && hasOpaqueTextBackground(cell)) {
                issues.add(issue(CanvasIssueType.OPAQUE_TEXT_BACKGROUND, "readability", "major", List.of(cell.getId()),
                        "Text cell has opaque background: " + cell.getId(), "candidate"));
            }
        }
    }

    private CanvasAnalysisIssue issue(CanvasIssueType type,
                                      String category,
                                      String severity,
                                      List<String> targetCellIds,
                                      String message,
                                      String repairability) {
        return CanvasAnalysisIssue.builder()
                .type(type)
                .category(category)
                .severity(severity)
                .targetCellIds(targetCellIds)
                .message(message)
                .repairability(repairability)
                .build();
    }

    private String resolveSeverity(List<CanvasAnalysisIssue> issues) {
        if (issues.isEmpty()) {
            return "ok";
        }
        for (CanvasAnalysisIssue issue : issues) {
            if ("critical".equals(issue.getSeverity())) {
                return "critical";
            }
        }
        return "major";
    }

    private CanvasSummaryData summary(List<CanvasCellData> cells) {
        List<CanvasCellData> nodes = cells.stream().filter(cell -> "node".equals(cell.getKind())).toList();
        int edgeCount = (int) cells.stream().filter(cell -> "edge".equals(cell.getKind())).count();
        Bounds bounds = bounds(nodes);
        String labels = nodes.stream()
                .map(CanvasCellData::getLabel)
                .filter(StringUtils::isNotBlank)
                .limit(8)
                .collect(Collectors.joining(", "));
        return CanvasSummaryData.builder()
                .nodeCount(nodes.size())
                .edgeCount(edgeCount)
                .x(bounds.x)
                .y(bounds.y)
                .width(bounds.width)
                .height(bounds.height)
                .summary("The canvas contains " + nodes.size() + " nodes and " + edgeCount + " edges. Main labels: " + labels + ".")
                .build();
    }

    private Bounds bounds(List<CanvasCellData> nodes) {
        if (nodes.isEmpty()) {
            return new Bounds(0D, 0D, 0D, 0D);
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (CanvasCellData node : nodes) {
            minX = Math.min(minX, node.getX());
            minY = Math.min(minY, node.getY());
            maxX = Math.max(maxX, node.maxX());
            maxY = Math.max(maxY, node.maxY());
        }
        return new Bounds(minX, minY, Math.max(0D, maxX - minX), Math.max(0D, maxY - minY));
    }

    private boolean isLikelyParentChild(CanvasCellData left, CanvasCellData right) {
        return StringUtils.equals(left.getId(), right.getParentId())
                || StringUtils.equals(right.getId(), left.getParentId());
    }

    private boolean isTextCell(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.startsWith("text;") || style.contains("shape=text");
    }

    private boolean hasOpaqueTextBackground(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("fillcolor=#ffffff")
                || style.contains("fillcolor=white")
                || style.contains("labelbackgroundcolor=#ffffff")
                || style.contains("labelbackgroundcolor=white")
                || style.contains("strokecolor=#ffffff")
                || style.contains("labelbordercolor=#ffffff");
    }

    private String resolveKind(Element cell) {
        if ("1".equals(cell.attributeValue("vertex"))) {
            return "node";
        }
        if ("1".equals(cell.attributeValue("edge"))) {
            return "edge";
        }
        return "cell";
    }

    private String toGraphModel(String xml) {
        String normalized = normalizeXml(xml);
        String graphModel = extractGraphModel(normalized);
        if (StringUtils.isNotBlank(graphModel)) {
            return graphModel;
        }
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + normalized
                + "</root></mxGraphModel>";
    }

    private String normalizeXml(String xml) {
        return StringUtils.trimToEmpty(xml)
                .replace("```xml", "")
                .replace("```", "")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\/", "/")
                .trim();
    }

    private String extractGraphModel(String xml) {
        int xmlStart = xml.indexOf("<mxGraphModel");
        int xmlEnd = xml.lastIndexOf("</mxGraphModel>");
        if (xmlStart < 0 || xmlEnd < xmlStart) {
            return "";
        }
        return xml.substring(xmlStart, xmlEnd + "</mxGraphModel>".length());
    }

    private String cleanLabel(String label) {
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

    private double number(Element geometry, String attribute) {
        if (geometry == null) {
            return 0D;
        }
        String raw = geometry.attributeValue(attribute);
        if (StringUtils.isBlank(raw)) {
            return 0D;
        }
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private record Bounds(double x, double y, double width, double height) {
    }

}
