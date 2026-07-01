package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DrawioCanvasXmlToolkit {

    private static final double LABEL_OFFSET = 22D;
    private static final double[] LABEL_OFFSETS = new double[]{22D, 36D, 50D};
    private static final double NODE_CLEARANCE = 10D;
    private static final double ROUTE_DOGLEG = 40D;
    private static final double MIN_LABEL_WIDTH = 48D;
    private static final double MAX_LABEL_WIDTH = 180D;
    private static final double LABEL_HEIGHT = 24D;

    private final ICanvasAnalyzer canvasAnalyzer;

    public DrawioCanvasXmlToolkit() {
        this(new DefaultCanvasAnalyzer());
    }

    DrawioCanvasXmlToolkit(ICanvasAnalyzer canvasAnalyzer) {
        this.canvasAnalyzer = canvasAnalyzer;
    }

    public String toGraphModel(String xml) {
        String normalized = normalizeXml(xml);
        String graphModel = extractGraphModel(normalized);
        if (StringUtils.isNotBlank(graphModel)) {
            return graphModel;
        }

        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + normalized
                + "</root></mxGraphModel>";
    }

    public CanvasInspection inspect(String xml) {
        CanvasAnalysis analysis = analyze(xml);
        return CanvasInspection.builder()
                .valid(analysis.isValid())
                .severity(analysis.getSeverity())
                .issues(analysis.getIssues().stream().map(CanvasAnalysisIssue::getMessage).toList())
                .cells(analysis.getCells().stream().map(this::toCellInfo).toList())
                .summary(analysis.getSummary().getSummary())
                .build();
    }

    public CanvasAnalysis analyze(String xml) {
        return canvasAnalyzer.analyze(xml, "unknown");
    }

    public List<OverlapInfo> detectOverlaps(String xml) {
        CanvasAnalysis analysis = analyze(xml);
        Map<String, CanvasCellData> cellsById = analysis.getCells().stream()
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        return analysis.getIssues().stream()
                .filter(issue -> CanvasIssueType.NODE_OVERLAP == issue.getType())
                .map(issue -> toOverlapInfo(issue, cellsById))
                .toList();
    }

    public List<CellInfo> findCells(String xml, String query) {
        CanvasInspection inspection = inspect(xml);
        if (!inspection.isValid() && inspection.getCells().isEmpty()) {
            return List.of();
        }

        String normalizedQuery = StringUtils.trimToEmpty(query).toLowerCase(Locale.ROOT);
        return inspection.getCells().stream()
                .filter(cell -> StringUtils.isBlank(normalizedQuery) || cell.matches(normalizedQuery))
                .collect(Collectors.toList());
    }

    private CellInfo toCellInfo(CanvasCellData cell) {
        return CellInfo.builder()
                .id(cell.getId())
                .label(cell.getLabel())
                .kind(cell.getKind())
                .style(cell.getStyle())
                .parentId(cell.getParentId())
                .source(cell.getSource())
                .target(cell.getTarget())
                .x(cell.getX())
                .y(cell.getY())
                .width(cell.getWidth())
                .height(cell.getHeight())
                .rawXml(cell.getRawXml())
                .build();
    }

    private OverlapInfo toOverlapInfo(CanvasAnalysisIssue issue, Map<String, CanvasCellData> cellsById) {
        List<String> targets = issue.getTargetCellIds();
        CanvasCellData left = targets.size() > 0 ? cellsById.get(targets.get(0)) : null;
        CanvasCellData right = targets.size() > 1 ? cellsById.get(targets.get(1)) : null;
        double overlapWidth = 0D;
        double overlapHeight = 0D;
        if (left != null && right != null) {
            overlapWidth = Math.min(left.maxX(), right.maxX()) - Math.max(left.getX(), right.getX());
            overlapHeight = Math.min(left.maxY(), right.maxY()) - Math.max(left.getY(), right.getY());
        }
        return OverlapInfo.builder()
                .sourceId(targets.size() > 0 ? targets.get(0) : "")
                .targetId(targets.size() > 1 ? targets.get(1) : "")
                .overlapWidth(overlapWidth)
                .overlapHeight(overlapHeight)
                .build();
    }

    public String replaceCells(String xml, String replacementCells) {
        try {
            Document document = DocumentHelper.parseText(toGraphModel(xml));
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return toGraphModel(xml);
            }

            Map<String, Element> replacements = replacementCellMap(replacementCells);
            if (replacements.isEmpty()) {
                return document.asXML();
            }

            Set<String> replaced = new HashSet<>();
            for (Element cell : new ArrayList<Element>(root.elements("mxCell"))) {
                String id = cell.attributeValue("id");
                Element replacement = replacements.get(id);
                if (replacement == null) {
                    continue;
                }
                root.remove(cell);
                root.add(replacement.detach());
                replaced.add(id);
            }

            for (Map.Entry<String, Element> entry : replacements.entrySet()) {
                if (!replaced.contains(entry.getKey())) {
                    root.add(entry.getValue().detach());
                }
            }
            return document.asXML();
        } catch (Exception ignored) {
            return toGraphModel(xml);
        }
    }

    public String routeEdges(String xml) {
        try {
            Document document = DocumentHelper.parseText(toGraphModel(xml));
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return toGraphModel(xml);
            }

            List<CellInfo> cells = readCells(root);
            normalizeAbsoluteCoordinates(cells);
            Map<String, CellInfo> nodes = cells.stream()
                    .filter(cell -> "node".equals(cell.getKind()))
                    .collect(Collectors.toMap(CellInfo::getId, cell -> cell, (left, right) -> left));

            for (Object item : root.elements("mxCell")) {
                Element edge = (Element) item;
                if (!"1".equals(edge.attributeValue("edge"))) {
                    continue;
                }
                CellInfo source = nodes.get(edge.attributeValue("source"));
                CellInfo target = nodes.get(edge.attributeValue("target"));
                if (source == null || target == null) {
                    continue;
                }
                routeEdge(document, edge, source, target, cells);
            }
            return document.asXML();
        } catch (Exception ignored) {
            return toGraphModel(xml);
        }
    }

    public String edgeCells(String xml) {
        try {
            Document document = DocumentHelper.parseText(toGraphModel(xml));
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return "";
            }

            StringBuilder cells = new StringBuilder();
            for (Object item : root.elements("mxCell")) {
                Element cell = (Element) item;
                if ("1".equals(cell.attributeValue("edge"))) {
                    cells.append(cell.asXML());
                }
            }
            return cells.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private Map<String, Element> replacementCellMap(String replacementCells) throws Exception {
        Document document = DocumentHelper.parseText(toGraphModel(replacementCells));
        Element root = document.getRootElement().element("root");
        Map<String, Element> replacements = new HashMap<>();
        if (root == null) {
            return replacements;
        }

        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            String id = cell.attributeValue("id");
            if (StringUtils.isBlank(id) || "0".equals(id) || "1".equals(id)) {
                continue;
            }
            replacements.put(id, cell);
        }
        return replacements;
    }

    private List<CellInfo> readCells(Element root) {
        List<CellInfo> cells = new ArrayList<>();
        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            String id = StringUtils.defaultString(cell.attributeValue("id"));
            if ("0".equals(id) || "1".equals(id)) {
                continue;
            }

            Element geometry = cell.element("mxGeometry");
            cells.add(CellInfo.builder()
                    .id(id)
                    .label(cleanLabel(cell.attributeValue("value")))
                    .kind(resolveKind(cell))
                    .style(StringUtils.defaultString(cell.attributeValue("style")))
                    .parentId(StringUtils.defaultString(cell.attributeValue("parent")))
                    .source(StringUtils.defaultString(cell.attributeValue("source")))
                    .target(StringUtils.defaultString(cell.attributeValue("target")))
                    .x(number(geometry, "x"))
                    .y(number(geometry, "y"))
                    .width(number(geometry, "width"))
                    .height(number(geometry, "height"))
                    .rawXml(cell.asXML())
                    .build());
        }
        return cells;
    }

    private void normalizeAbsoluteCoordinates(List<CellInfo> cells) {
        Map<String, CellInfo> firstCellById = new HashMap<>();
        for (CellInfo cell : cells) {
            firstCellById.putIfAbsent(cell.getId(), cell);
        }

        Set<String> resolved = new HashSet<>();
        for (CellInfo cell : cells) {
            resolvePosition(cell, firstCellById, resolved, new HashSet<>());
        }
    }

    private void resolvePosition(CellInfo cell,
                                 Map<String, CellInfo> cellById,
                                 Set<String> resolved,
                                 Set<String> resolving) {
        if (cell == null || resolved.contains(cell.getId()) || resolving.contains(cell.getId())) {
            return;
        }
        resolving.add(cell.getId());
        CellInfo parent = cellById.get(cell.getParentId());
        if (parent != null && "node".equals(parent.getKind())) {
            resolvePosition(parent, cellById, resolved, resolving);
            cell.setX(parent.getX() + cell.getX());
            cell.setY(parent.getY() + cell.getY());
        }
        resolving.remove(cell.getId());
        resolved.add(cell.getId());
    }

    private boolean isTextCell(CellInfo cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.startsWith("text;") || style.contains("shape=text");
    }

    private void routeEdge(Document document, Element edge, CellInfo source, CellInfo target, List<CellInfo> cells) {
        boolean horizontal = Math.abs(target.centerX() - source.centerX()) >= Math.abs(target.centerY() - source.centerY());
        boolean forward = horizontal ? target.centerX() >= source.centerX() : target.centerY() >= source.centerY();
        String style = ensureStyleTokens(StringUtils.defaultString(edge.attributeValue("style")), horizontal, forward);
        edge.addAttribute("style", style);

        Element geometry = edge.element("mxGeometry");
        if (geometry == null) {
            geometry = edge.addElement("mxGeometry");
            geometry.addAttribute("as", "geometry");
        }
        geometry.addAttribute("relative", "1");

        List<CanvasPoint2D> originalWaypoints = readWaypoints(geometry);
        List<CanvasPoint2D> baseWaypoints = originalWaypoints.isEmpty()
                ? defaultWaypoints(source, target, horizontal)
                : originalWaypoints;
        replaceWaypoints(geometry, baseWaypoints);

        if (hasEdgeNodeCrossing(document.asXML(), edge.attributeValue("id"))) {
            List<CanvasPoint2D> best = null;
            double bestLength = Double.MAX_VALUE;
            for (List<CanvasPoint2D> candidate : routeCandidates(source, target, cells, horizontal, forward)) {
                replaceWaypoints(geometry, candidate);
                if (hasEdgeNodeCrossing(document.asXML(), edge.attributeValue("id"))) {
                    continue;
                }
                double length = routeLength(edgeRoutePoints(geometry, source, target, horizontal, forward));
                if (length < bestLength) {
                    best = candidate;
                    bestLength = length;
                }
            }
            replaceWaypoints(geometry, best == null ? originalWaypoints : best);
        }
        positionEdgeLabel(edge, geometry, source, target, cells, horizontal, forward);
    }

    private boolean hasEdgeNodeCrossing(String xml, String edgeId) {
        return canvasAnalyzer.analyze(xml, "unknown").getIssues().stream()
                .anyMatch(issue -> CanvasIssueType.EDGE_NODE_CROSSING == issue.getType()
                        && !issue.getTargetCellIds().isEmpty()
                        && StringUtils.equals(edgeId, issue.getTargetCellIds().get(0)));
    }

    private List<CanvasPoint2D> readWaypoints(Element geometry) {
        Element waypointArray = waypointArray(geometry);
        if (waypointArray == null) {
            return List.of();
        }

        List<CanvasPoint2D> waypoints = new ArrayList<>();
        for (Object item : waypointArray.elements("mxPoint")) {
            Element point = (Element) item;
            waypoints.add(new CanvasPoint2D(number(point, "x"), number(point, "y")));
        }
        return waypoints;
    }

    private Element waypointArray(Element geometry) {
        if (geometry == null) {
            return null;
        }
        for (Object item : geometry.elements("Array")) {
            Element array = (Element) item;
            if (StringUtils.equals("points", array.attributeValue("as"))) {
                return array;
            }
        }
        return null;
    }

    private void replaceWaypoints(Element geometry, List<CanvasPoint2D> waypoints) {
        for (Element array : new ArrayList<Element>(geometry.elements("Array"))) {
            if (StringUtils.equals("points", array.attributeValue("as"))) {
                geometry.remove(array);
            }
        }
        if (waypoints.isEmpty()) {
            return;
        }

        Element points = geometry.addElement("Array");
        points.addAttribute("as", "points");
        for (CanvasPoint2D waypoint : waypoints) {
            addPoint(points, waypoint.getX(), waypoint.getY());
        }
    }

    private List<CanvasPoint2D> defaultWaypoints(CellInfo source, CellInfo target, boolean horizontal) {
        if (horizontal) {
            double midX = (source.centerX() + target.centerX()) / 2D;
            return List.of(new CanvasPoint2D(midX, source.centerY()), new CanvasPoint2D(midX, target.centerY()));
        }

        double midY = (source.centerY() + target.centerY()) / 2D;
        return List.of(new CanvasPoint2D(source.centerX(), midY), new CanvasPoint2D(target.centerX(), midY));
    }

    private List<List<CanvasPoint2D>> routeCandidates(CellInfo source,
                                                      CellInfo target,
                                                      List<CellInfo> cells,
                                                      boolean horizontal,
                                                      boolean forward) {
        List<CellInfo> blockers = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> !StringUtils.equals(cell.getId(), source.getId()))
                .filter(cell -> !StringUtils.equals(cell.getId(), target.getId()))
                .filter(cell -> !isTextCell(cell))
                .filter(cell -> !isBoundaryCell(cell))
                .toList();
        if (blockers.isEmpty()) {
            return List.of();
        }

        return horizontal
                ? horizontalRouteCandidates(source, target, blockers, forward)
                : verticalRouteCandidates(source, target, blockers, forward);
    }

    private List<List<CanvasPoint2D>> horizontalRouteCandidates(CellInfo source,
                                                               CellInfo target,
                                                               List<CellInfo> blockers,
                                                               boolean forward) {
        CanvasPoint2D sourceAnchor = sourceAnchor(source, true, forward);
        CanvasPoint2D targetAnchor = targetAnchor(target, true, forward);
        double direction = forward ? 1D : -1D;
        double startX = sourceAnchor.getX() + ROUTE_DOGLEG * direction;
        double endX = targetAnchor.getX() - ROUTE_DOGLEG * direction;
        Set<Double> lanes = new java.util.LinkedHashSet<>();
        for (CellInfo blocker : blockers) {
            lanes.add(blocker.getY() - NODE_CLEARANCE);
            lanes.add(blocker.maxY() + NODE_CLEARANCE);
        }

        List<List<CanvasPoint2D>> candidates = new ArrayList<>();
        for (double laneY : lanes) {
            candidates.add(List.of(
                    new CanvasPoint2D(startX, sourceAnchor.getY()),
                    new CanvasPoint2D(startX, laneY),
                    new CanvasPoint2D(endX, laneY),
                    new CanvasPoint2D(endX, targetAnchor.getY())
            ));
        }
        return candidates;
    }

    private List<List<CanvasPoint2D>> verticalRouteCandidates(CellInfo source,
                                                             CellInfo target,
                                                             List<CellInfo> blockers,
                                                             boolean forward) {
        CanvasPoint2D sourceAnchor = sourceAnchor(source, false, forward);
        CanvasPoint2D targetAnchor = targetAnchor(target, false, forward);
        double direction = forward ? 1D : -1D;
        double startY = sourceAnchor.getY() + ROUTE_DOGLEG * direction;
        double endY = targetAnchor.getY() - ROUTE_DOGLEG * direction;
        Set<Double> lanes = new java.util.LinkedHashSet<>();
        for (CellInfo blocker : blockers) {
            lanes.add(blocker.getX() - NODE_CLEARANCE);
            lanes.add(blocker.maxX() + NODE_CLEARANCE);
        }

        List<List<CanvasPoint2D>> candidates = new ArrayList<>();
        for (double laneX : lanes) {
            candidates.add(List.of(
                    new CanvasPoint2D(sourceAnchor.getX(), startY),
                    new CanvasPoint2D(laneX, startY),
                    new CanvasPoint2D(laneX, endY),
                    new CanvasPoint2D(targetAnchor.getX(), endY)
            ));
        }
        return candidates;
    }

    private void positionEdgeLabel(Element edge,
                                   Element geometry,
                                   CellInfo source,
                                   CellInfo target,
                                   List<CellInfo> cells,
                                   boolean horizontal,
                                   boolean forward) {
        String label = cleanLabel(edge.attributeValue("value"));
        if (StringUtils.isBlank(label)) {
            return;
        }

        List<CanvasPoint2D> route = edgeRoutePoints(geometry, source, target, horizontal, forward);
        List<RouteSegment> segments = routeSegments(route);
        if (segments.isEmpty()) {
            return;
        }

        double totalLength = segments.stream().mapToDouble(RouteSegment::length).sum();
        double labelWidth = labelWidth(label);
        List<CellInfo> blockers = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> !isTextCell(cell))
                .toList();

        LabelCandidate best = null;
        double lengthBefore = 0D;
        for (int index = 0; index < segments.size(); index++) {
            RouteSegment segment = segments.get(index);
            double segmentLength = segment.length();
            if (segmentLength < 1D) {
                continue;
            }

            for (double offset : LABEL_OFFSETS) {
                for (double side : new double[]{-1D, 1D}) {
                    LabelCandidate candidate = labelCandidate(segment, index, segments.size(), lengthBefore, totalLength, labelWidth, side, offset);
                    double score = scoreLabelCandidate(candidate, blockers, source, target);
                    candidate.setScore(score);
                    if (best == null || candidate.getScore() > best.getScore()) {
                        best = candidate;
                    }
                }
            }
            lengthBefore += segmentLength;
        }

        if (best != null) {
            geometry.addAttribute("x", trimNumber(best.getRelativeX()));
            geometry.addAttribute("y", trimNumber(best.getOffset()));
        }
    }

    private List<CanvasPoint2D> edgeRoutePoints(Element geometry,
                                                CellInfo source,
                                                CellInfo target,
                                                boolean horizontal,
                                                boolean forward) {
        List<CanvasPoint2D> points = new ArrayList<>();
        points.add(sourceAnchor(source, horizontal, forward));

        Element waypointArray = geometry.element("Array");
        if (waypointArray != null) {
            for (Object item : waypointArray.elements("mxPoint")) {
                Element point = (Element) item;
                points.add(new CanvasPoint2D(number(point, "x"), number(point, "y")));
            }
        }

        points.add(targetAnchor(target, horizontal, forward));
        return points;
    }

    private CanvasPoint2D sourceAnchor(CellInfo source, boolean horizontal, boolean forward) {
        if (horizontal) {
            return new CanvasPoint2D(forward ? source.maxX() : source.getX(), source.centerY());
        }
        return new CanvasPoint2D(source.centerX(), forward ? source.maxY() : source.getY());
    }

    private CanvasPoint2D targetAnchor(CellInfo target, boolean horizontal, boolean forward) {
        if (horizontal) {
            return new CanvasPoint2D(forward ? target.getX() : target.maxX(), target.centerY());
        }
        return new CanvasPoint2D(target.centerX(), forward ? target.getY() : target.maxY());
    }

    private List<RouteSegment> routeSegments(List<CanvasPoint2D> points) {
        List<RouteSegment> segments = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            RouteSegment segment = new RouteSegment(points.get(i), points.get(i + 1));
            if (segment.length() > 0D) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private double routeLength(List<CanvasPoint2D> points) {
        return routeSegments(points).stream().mapToDouble(RouteSegment::length).sum();
    }

    private LabelCandidate labelCandidate(RouteSegment segment,
                                          int segmentIndex,
                                          int segmentCount,
                                          double lengthBefore,
                                          double totalLength,
                                          double labelWidth,
                                          double side,
                                          double offset) {
        CanvasPoint2D midpoint = segment.midpoint();
        CanvasPoint2D normal = segment.normal(side);
        double centerX = midpoint.getX() + normal.getX() * offset;
        double centerY = midpoint.getY() + normal.getY() * offset;
        double distanceAtMidpoint = lengthBefore + segment.length() / 2D;
        double relativeX = totalLength == 0D ? 0D : (distanceAtMidpoint / totalLength) * 2D - 1D;

        return LabelCandidate.builder()
                .box(LabelBox.centered(centerX, centerY, labelWidth, LABEL_HEIGHT))
                .offset(side * offset)
                .relativeX(clamp(relativeX, -1D, 1D))
                .segmentIndex(segmentIndex)
                .segmentCount(segmentCount)
                .segmentLength(segment.length())
                .middleDistance(Math.abs(0.5D - (totalLength == 0D ? 0.5D : distanceAtMidpoint / totalLength)))
                .absoluteOffset(offset)
                .build();
    }

    private double scoreLabelCandidate(LabelCandidate candidate,
                                       List<CellInfo> blockers,
                                       CellInfo source,
                                       CellInfo target) {
        // Score simple candidate boxes instead of doing expensive global diagram layout.
        double score = candidate.getSegmentLength() - candidate.getMiddleDistance() * 80D;
        if (candidate.getSegmentIndex() > 0 && candidate.getSegmentIndex() < candidate.getSegmentCount() - 1) {
            score += 120D;
        } else {
            score -= 40D;
        }
        score -= Math.max(0D, candidate.getAbsoluteOffset() - LABEL_OFFSET) * 2D;

        for (CellInfo blocker : blockers) {
            if (canIgnoreBlocker(blocker, candidate.getBox(), source, target)) {
                continue;
            }
            if (candidate.getBox().intersects(blocker)) {
                score -= 10_000D + candidate.getBox().intersectionArea(blocker);
                continue;
            }
            double distance = candidate.getBox().distanceTo(blocker);
            if (distance < NODE_CLEARANCE) {
                score -= (NODE_CLEARANCE - distance) * 40D;
            }
        }
        return score;
    }

    private boolean canIgnoreBlocker(CellInfo blocker, LabelBox labelBox, CellInfo source, CellInfo target) {
        if (StringUtils.equals(blocker.getId(), source.getParentId()) || StringUtils.equals(blocker.getId(), target.getParentId())) {
            return true;
        }
        return isBoundaryCell(blocker) && labelBox.inside(blocker);
    }

    private boolean isBoundaryCell(CellInfo cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("swimlane") || style.contains("startsize=") || style.contains("container=1");
    }

    private double labelWidth(String label) {
        return clamp(cleanLabel(label).length() * 7D + 18D, MIN_LABEL_WIDTH, MAX_LABEL_WIDTH);
    }

    private String trimNumber(double value) {
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.0001D) {
            return String.valueOf(rounded);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String ensureStyleTokens(String style, boolean horizontal, boolean forward) {
        Map<String, String> tokens = parseStyle(style);
        tokens.put("edgeStyle", "orthogonalEdgeStyle");
        tokens.put("rounded", "0");
        tokens.put("orthogonalLoop", "1");
        tokens.put("jettySize", "auto");
        tokens.put("html", "1");
        tokens.put("exitX", horizontal ? (forward ? "1" : "0") : "0.5");
        tokens.put("exitY", horizontal ? "0.5" : (forward ? "1" : "0"));
        tokens.put("entryX", horizontal ? (forward ? "0" : "1") : "0.5");
        tokens.put("entryY", horizontal ? "0.5" : (forward ? "0" : "1"));
        return tokens.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(";")) + ";";
    }

    private Map<String, String> parseStyle(String style) {
        Map<String, String> tokens = new java.util.LinkedHashMap<>();
        for (String token : StringUtils.defaultString(style).split(";")) {
            if (StringUtils.isBlank(token)) {
                continue;
            }
            int equalsIndex = token.indexOf('=');
            if (equalsIndex <= 0) {
                tokens.put(token, "1");
            } else {
                tokens.put(token.substring(0, equalsIndex), token.substring(equalsIndex + 1));
            }
        }
        return tokens;
    }

    private void addPoint(Element points, double x, double y) {
        Element point = points.addElement("mxPoint");
        point.addAttribute("x", String.valueOf(Math.round(x)));
        point.addAttribute("y", String.valueOf(Math.round(y)));
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

    @Data
    @Builder
    public static class CanvasInspection {
        private boolean valid;
        private String severity;
        private List<String> issues;
        private List<CellInfo> cells;
        private String summary;

        static CanvasInspection invalid(String severity, List<String> issues) {
            return CanvasInspection.builder()
                    .valid(false)
                    .severity(severity)
                    .issues(issues)
                    .cells(List.of())
                    .summary("Canvas inspection failed.")
                    .build();
        }
    }

    @Data
    @Builder
    public static class CellInfo {
        private String id;
        private String label;
        private String kind;
        private String style;
        private String parentId;
        private String source;
        private String target;
        private double x;
        private double y;
        private double width;
        private double height;
        private String rawXml;

        double centerX() {
            return x + width / 2D;
        }

        double centerY() {
            return y + height / 2D;
        }

        double maxX() {
            return x + width;
        }

        double maxY() {
            return y + height;
        }

        boolean matches(String query) {
            return contains(id, query)
                    || contains(label, query)
                    || contains(style, query)
                    || contains(source, query)
                    || contains(target, query);
        }

        private boolean contains(String value, String query) {
            return StringUtils.defaultString(value).toLowerCase(Locale.ROOT).contains(query);
        }
    }

    @Data
    @Builder
    public static class OverlapInfo {
        private String sourceId;
        private String targetId;
        private double overlapWidth;
        private double overlapHeight;
    }

    @Data
    private static class CanvasPoint2D {
        private final double x;
        private final double y;
    }

    @Data
    private static class RouteSegment {
        private final CanvasPoint2D start;
        private final CanvasPoint2D end;

        double length() {
            return Math.hypot(end.getX() - start.getX(), end.getY() - start.getY());
        }

        CanvasPoint2D midpoint() {
            return new CanvasPoint2D((start.getX() + end.getX()) / 2D, (start.getY() + end.getY()) / 2D);
        }

        CanvasPoint2D normal(double side) {
            double length = length();
            if (length == 0D) {
                return new CanvasPoint2D(0D, side);
            }
            double dx = (end.getX() - start.getX()) / length;
            double dy = (end.getY() - start.getY()) / length;
            return new CanvasPoint2D(-dy * side, dx * side);
        }
    }

    @Data
    @Builder
    private static class LabelCandidate {
        private LabelBox box;
        private double offset;
        private double relativeX;
        private int segmentIndex;
        private int segmentCount;
        private double segmentLength;
        private double middleDistance;
        private double absoluteOffset;
        private double score;
    }

    @Data
    @Builder
    private static class LabelBox {
        private double x;
        private double y;
        private double width;
        private double height;

        static LabelBox centered(double centerX, double centerY, double width, double height) {
            return LabelBox.builder()
                    .x(centerX - width / 2D)
                    .y(centerY - height / 2D)
                    .width(width)
                    .height(height)
                    .build();
        }

        boolean intersects(CellInfo cell) {
            return x < cell.maxX()
                    && x + width > cell.getX()
                    && y < cell.maxY()
                    && y + height > cell.getY();
        }

        boolean inside(CellInfo cell) {
            return x >= cell.getX()
                    && y >= cell.getY()
                    && x + width <= cell.maxX()
                    && y + height <= cell.maxY();
        }

        double intersectionArea(CellInfo cell) {
            double overlapWidth = Math.min(x + width, cell.maxX()) - Math.max(x, cell.getX());
            double overlapHeight = Math.min(y + height, cell.maxY()) - Math.max(y, cell.getY());
            if (overlapWidth <= 0D || overlapHeight <= 0D) {
                return 0D;
            }
            return overlapWidth * overlapHeight;
        }

        double distanceTo(CellInfo cell) {
            double dx = Math.max(Math.max(cell.getX() - (x + width), x - cell.maxX()), 0D);
            double dy = Math.max(Math.max(cell.getY() - (y + height), y - cell.maxY()), 0D);
            return Math.hypot(dx, dy);
        }
    }
}
