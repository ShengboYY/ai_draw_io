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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrawioCanvasXmlToolkit {

    private static final double LABEL_OFFSET = 32D;
    private static final double[] LABEL_OFFSETS = new double[]{32D, 44D, 56D};
    private static final double NODE_CLEARANCE = 10D;
    private static final double ROUTE_DOGLEG = 40D;
    private static final double MIN_LABEL_WIDTH = 48D;
    private static final double MAX_LABEL_WIDTH = 180D;
    private static final double LABEL_HEIGHT = 24D;
    private static final double PORT_SAFE_MIN = 0.25D;
    private static final double PORT_SAFE_MAX = 0.75D;
    private static final Pattern VALUE_ATTRIBUTE_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_.:-])(value\\s*=\\s*)(['\"])(.*?)\\2", Pattern.DOTALL);

    private final ICanvasAnalyzer canvasAnalyzer;

    public DrawioCanvasXmlToolkit() {
        this(new DefaultCanvasAnalyzer());
    }

    DrawioCanvasXmlToolkit(ICanvasAnalyzer canvasAnalyzer) {
        this.canvasAnalyzer = canvasAnalyzer;
    }

    public String toGraphModel(String xml) {
        String normalized = normalizeXml(xml);
        normalized = sanitizeValueAttributes(normalized);
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

    /**
     * Deterministically repair auto-fixable geometry (edge/node body crossings) by rerouting edges,
     * returning the input unchanged when there is nothing to auto-fix. Used by the localized patch merge
     * path so incremental edits get the same geometry safety net as full-canvas mutations.
     */
    public String repairGeometryIfNeeded(String xml) {
        CanvasAnalysis analysis = analyze(xml);
        boolean needsReroute = analysis.getIssues().stream()
                .anyMatch(issue -> (CanvasIssueType.EDGE_NODE_CROSSING == issue.getType()
                        || CanvasIssueType.EDGE_LABEL_COLLISION == issue.getType()
                        || CanvasIssueType.PORT_DIRECTION_MISMATCH == issue.getType()
                        || CanvasIssueType.PARALLEL_EDGE_OVERLAP == issue.getType()
                        || CanvasIssueType.NODE_SIDE_PORT_CROWDING == issue.getType()
                        || CanvasIssueType.PORT_CORNER_PROXIMITY == issue.getType())
                        && "auto_reroute".equals(issue.getRepairability()));
        return needsReroute ? routeEdges(xml) : xml;
    }

    /**
     * Deterministic structural repair for model-authored XML. Fixes only what code can fix
     * reliably — nested cells, duplicate ids, port attributes outside the style string, missing
     * geometry, and dangling edge references — so a draft is never discarded for a mechanical
     * mistake. Visual quality issues stay with the analyzer and the drawing loop.
     */
    public String autoRepair(String xml) {
        String wrapped = toGraphModel(xml);
        try {
            Document document = DocumentHelper.parseText(wrapped);
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return wrapped;
            }
            boolean changed = flattenNestedCells(root);
            changed |= deduplicateCellIds(root);
            changed |= movePortAttributesIntoStyle(root);
            changed |= ensureGeometry(root);
            changed |= repairEdgeEndpoints(root);
            // Preserve the caller's original text when nothing needed fixing; re-serialization
            // would needlessly normalize quotes and formatting.
            return changed ? document.getRootElement().asXML() : wrapped;
        } catch (Exception ignored) {
            // Unparseable even after normalization; return the wrapped input so the caller's
            // inspection reports the parse failure instead of this repair pass masking it.
            return wrapped;
        }
    }

    private boolean flattenNestedCells(Element root) {
        // Draw.io ignores mxCell elements nested inside another mxCell; hoist them to root
        // siblings and keep the intended grouping through the parent attribute.
        boolean changed = false;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Element cell : new ArrayList<Element>(root.elements("mxCell"))) {
                for (Element nested : new ArrayList<Element>(cell.elements("mxCell"))) {
                    if (StringUtils.isBlank(nested.attributeValue("parent"))
                            && StringUtils.isNotBlank(cell.attributeValue("id"))) {
                        nested.addAttribute("parent", cell.attributeValue("id"));
                    }
                    nested.detach();
                    root.add(nested);
                    moved = true;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean deduplicateCellIds(Element root) {
        boolean changed = false;
        Set<String> seen = new HashSet<>();
        for (Element cell : new ArrayList<Element>(root.elements("mxCell"))) {
            String id = cell.attributeValue("id");
            if (StringUtils.isBlank(id) || "0".equals(id) || "1".equals(id)) {
                continue;
            }
            if (!seen.add(id)) {
                // References keep resolving to the first occurrence; the later duplicate gets a
                // fresh id so both cells stay visible instead of one silently replacing the other.
                int suffix = 2;
                String candidate = id + "-r" + suffix;
                while (seen.contains(candidate)) {
                    candidate = id + "-r" + (++suffix);
                }
                cell.addAttribute("id", candidate);
                seen.add(candidate);
                changed = true;
            }
        }
        return changed;
    }

    private static final String[] PORT_ATTRIBUTES = {"exitX", "exitY", "entryX", "entryY", "exitDx", "exitDy", "entryDx", "entryDy"};

    private boolean movePortAttributesIntoStyle(Element root) {
        boolean anyChanged = false;
        for (Element cell : new ArrayList<Element>(root.elements("mxCell"))) {
            String style = StringUtils.defaultString(cell.attributeValue("style"));
            boolean changed = false;
            for (String name : PORT_ATTRIBUTES) {
                String value = cell.attributeValue(name);
                if (StringUtils.isNotBlank(value)) {
                    if (!style.contains(name + "=")) {
                        style = StringUtils.appendIfMissing(StringUtils.isBlank(style) ? "" : style, ";");
                        style = style + name + "=" + value + ";";
                    }
                    cell.remove(cell.attribute(name));
                    changed = true;
                }
                for (Element strayTag : new ArrayList<Element>(cell.elements(name))) {
                    cell.remove(strayTag);
                    changed = true;
                }
            }
            if (changed) {
                cell.addAttribute("style", style);
                anyChanged = true;
            }
        }
        return anyChanged;
    }

    private boolean ensureGeometry(Element root) {
        boolean changed = false;
        double stagingY = 40D;
        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            Element geometry = cell.element("mxGeometry");
            if (geometry != null && geometry.attributeValue("y") != null) {
                double bottom = parseDouble(geometry.attributeValue("y")) + parseDouble(geometry.attributeValue("height"));
                stagingY = Math.max(stagingY, bottom + 40D);
            }
        }
        for (Element cell : new ArrayList<Element>(root.elements("mxCell"))) {
            String id = cell.attributeValue("id");
            if ("0".equals(id) || "1".equals(id)) {
                continue;
            }
            boolean isEdge = "1".equals(cell.attributeValue("edge"));
            Element geometry = cell.element("mxGeometry");
            if (isEdge) {
                if (geometry == null) {
                    geometry = cell.addElement("mxGeometry");
                    geometry.addAttribute("as", "geometry");
                    changed = true;
                }
                if (StringUtils.isBlank(geometry.attributeValue("relative"))) {
                    geometry.addAttribute("relative", "1");
                    changed = true;
                }
                continue;
            }
            if (!"1".equals(cell.attributeValue("vertex"))) {
                continue;
            }
            if (geometry == null) {
                geometry = cell.addElement("mxGeometry");
                geometry.addAttribute("as", "geometry");
                changed = true;
            }
            if (parseDouble(geometry.attributeValue("width")) <= 0) {
                geometry.addAttribute("width", "160");
                changed = true;
            }
            if (parseDouble(geometry.attributeValue("height")) <= 0) {
                geometry.addAttribute("height", "60");
                changed = true;
            }
            if (geometry.attributeValue("x") == null && geometry.attributeValue("y") == null) {
                // Stage repaired vertices in a visible column below existing content instead of
                // stacking them at the origin.
                geometry.addAttribute("x", "40");
                geometry.addAttribute("y", trimNumber(stagingY));
                stagingY += 90D;
                changed = true;
            }
        }
        return changed;
    }

    private boolean repairEdgeEndpoints(Element root) {
        boolean changed = false;
        Set<String> ids = new HashSet<>();
        Map<String, Element> cellsById = new HashMap<>();
        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            if (StringUtils.isNotBlank(cell.attributeValue("id"))) {
                ids.add(cell.attributeValue("id"));
                cellsById.putIfAbsent(cell.attributeValue("id"), cell);
            }
        }

        for (Element edge : new ArrayList<Element>(root.elements("mxCell"))) {
            if (!"1".equals(edge.attributeValue("edge"))) {
                continue;
            }
            String source = edge.attributeValue("source");
            String target = edge.attributeValue("target");
            boolean sourceBroken = StringUtils.isNotBlank(source) && !ids.contains(source);
            boolean targetBroken = StringUtils.isNotBlank(target) && !ids.contains(target);
            if (sourceBroken && targetBroken) {
                // Neither endpoint resolves; the edge carries no usable information.
                root.remove(edge);
                changed = true;
                continue;
            }
            if (sourceBroken) {
                edge.remove(edge.attribute("source"));
                anchorDanglingEndpoint(edge, cellsById.get(target), "sourcePoint");
                changed = true;
            }
            if (targetBroken) {
                edge.remove(edge.attribute("target"));
                anchorDanglingEndpoint(edge, cellsById.get(source), "targetPoint");
                changed = true;
            }

            boolean hasSource = StringUtils.isNotBlank(edge.attributeValue("source"));
            boolean hasTarget = StringUtils.isNotBlank(edge.attributeValue("target"));
            if (!hasSource && !hasTarget && namedPoint(edge, "sourcePoint") == null && namedPoint(edge, "targetPoint") == null) {
                root.remove(edge);
                changed = true;
            }
        }
        return changed;
    }

    private void anchorDanglingEndpoint(Element edge, Element remainingNode, String pointName) {
        if (namedPoint(edge, pointName) != null) {
            return;
        }
        Element geometry = edge.element("mxGeometry");
        if (geometry == null) {
            geometry = edge.addElement("mxGeometry");
            geometry.addAttribute("relative", "1");
            geometry.addAttribute("as", "geometry");
        }
        double x = 40D;
        double y = 40D;
        Element remainingGeometry = remainingNode == null ? null : remainingNode.element("mxGeometry");
        if (remainingGeometry != null) {
            double nodeX = parseDouble(remainingGeometry.attributeValue("x"));
            double nodeY = parseDouble(remainingGeometry.attributeValue("y"));
            double nodeH = parseDouble(remainingGeometry.attributeValue("height"));
            x = "sourcePoint".equals(pointName) ? Math.max(0, nodeX - 120D) : nodeX + 200D;
            y = nodeY + Math.max(nodeH / 2D, 20D);
        }
        Element point = geometry.addElement("mxPoint");
        point.addAttribute("x", trimNumber(x));
        point.addAttribute("y", trimNumber(y));
        point.addAttribute("as", pointName);
    }

    private Element namedPoint(Element edge, String pointName) {
        Element geometry = edge.element("mxGeometry");
        if (geometry == null) {
            return null;
        }
        for (Object item : geometry.elements("mxPoint")) {
            Element point = (Element) item;
            if (StringUtils.equals(pointName, point.attributeValue("as"))) {
                return point;
            }
        }
        return null;
    }

    private double parseDouble(String raw) {
        if (StringUtils.isBlank(raw)) {
            return 0D;
        }
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return 0D;
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
        EdgeRouteStyle routeStyle = resolveRouteStyle(edge, source, target, cells, horizontal, forward);
        String style = ensureStyleTokens(StringUtils.defaultString(edge.attributeValue("style")),
                horizontal, forward, routeStyle.getSourceTrackFraction(), routeStyle.getTargetTrackFraction(),
                routeStyle.isAuxiliary());
        edge.addAttribute("style", style);

        Element geometry = edge.element("mxGeometry");
        if (geometry == null) {
            geometry = edge.addElement("mxGeometry");
            geometry.addAttribute("as", "geometry");
        }
        geometry.addAttribute("relative", "1");

        List<CanvasPoint2D> originalWaypoints = readWaypoints(geometry);
        List<CanvasPoint2D> baseWaypoints = originalWaypoints.isEmpty()
                ? defaultWaypoints(source, target, horizontal, routeStyle)
                : originalWaypoints;
        replaceWaypoints(geometry, baseWaypoints);

        if (hasEdgeNodeCrossing(document.asXML(), edge.attributeValue("id"))) {
            List<CanvasPoint2D> best = null;
            double bestLength = Double.MAX_VALUE;
            for (List<CanvasPoint2D> candidate : routeCandidates(source, target, cells, horizontal, forward,
                    routeStyle)) {
                replaceWaypoints(geometry, candidate);
                if (hasEdgeNodeCrossing(document.asXML(), edge.attributeValue("id"))) {
                    continue;
                }
                double length = routeLength(edgeRoutePoints(geometry, source, target, horizontal, forward, routeStyle));
                if (length < bestLength) {
                    best = candidate;
                    bestLength = length;
                }
            }
            replaceWaypoints(geometry, best == null ? originalWaypoints : best);
        }
        positionEdgeLabel(edge, geometry, source, target, cells, horizontal, forward, routeStyle);
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

    private List<CanvasPoint2D> defaultWaypoints(CellInfo source, CellInfo target, boolean horizontal, EdgeRouteStyle routeStyle) {
        if (horizontal) {
            double midX = (source.centerX() + target.centerX()) / 2D;
            return List.of(new CanvasPoint2D(midX, source.trackY(routeStyle.getSourceTrackFraction())),
                    new CanvasPoint2D(midX, target.trackY(routeStyle.getTargetTrackFraction())));
        }

        double midY = (source.centerY() + target.centerY()) / 2D;
        return List.of(new CanvasPoint2D(source.trackX(routeStyle.getSourceTrackFraction()), midY),
                new CanvasPoint2D(target.trackX(routeStyle.getTargetTrackFraction()), midY));
    }

    private List<List<CanvasPoint2D>> routeCandidates(CellInfo source,
                                                      CellInfo target,
                                                      List<CellInfo> cells,
                                                      boolean horizontal,
                                                      boolean forward,
                                                      EdgeRouteStyle routeStyle) {
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
                ? horizontalRouteCandidates(source, target, blockers, forward, routeStyle)
                : verticalRouteCandidates(source, target, blockers, forward, routeStyle);
    }

    private List<List<CanvasPoint2D>> horizontalRouteCandidates(CellInfo source,
                                                               CellInfo target,
                                                               List<CellInfo> blockers,
                                                               boolean forward,
                                                               EdgeRouteStyle routeStyle) {
        CanvasPoint2D sourceAnchor = sourceAnchor(source, true, forward, routeStyle);
        CanvasPoint2D targetAnchor = targetAnchor(target, true, forward, routeStyle);
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
                                                             boolean forward,
                                                             EdgeRouteStyle routeStyle) {
        CanvasPoint2D sourceAnchor = sourceAnchor(source, false, forward, routeStyle);
        CanvasPoint2D targetAnchor = targetAnchor(target, false, forward, routeStyle);
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
                                   boolean forward,
                                   EdgeRouteStyle routeStyle) {
        String label = cleanLabel(edge.attributeValue("value"));
        if (StringUtils.isBlank(label)) {
            return;
        }

        List<CanvasPoint2D> route = edgeRoutePoints(geometry, source, target, horizontal, forward, routeStyle);
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
                                                boolean forward,
                                                EdgeRouteStyle routeStyle) {
        List<CanvasPoint2D> points = new ArrayList<>();
        points.add(sourceAnchor(source, horizontal, forward, routeStyle));

        Element waypointArray = geometry.element("Array");
        if (waypointArray != null) {
            for (Object item : waypointArray.elements("mxPoint")) {
                Element point = (Element) item;
                points.add(new CanvasPoint2D(number(point, "x"), number(point, "y")));
            }
        }

        points.add(targetAnchor(target, horizontal, forward, routeStyle));
        return points;
    }

    private CanvasPoint2D sourceAnchor(CellInfo source, boolean horizontal, boolean forward, EdgeRouteStyle routeStyle) {
        if (horizontal) {
            return new CanvasPoint2D(forward ? source.maxX() : source.getX(),
                    source.trackY(routeStyle.getSourceTrackFraction()));
        }
        return new CanvasPoint2D(source.trackX(routeStyle.getSourceTrackFraction()),
                forward ? source.maxY() : source.getY());
    }

    private CanvasPoint2D targetAnchor(CellInfo target, boolean horizontal, boolean forward, EdgeRouteStyle routeStyle) {
        if (horizontal) {
            return new CanvasPoint2D(forward ? target.getX() : target.maxX(),
                    target.trackY(routeStyle.getTargetTrackFraction()));
        }
        return new CanvasPoint2D(target.trackX(routeStyle.getTargetTrackFraction()),
                forward ? target.getY() : target.maxY());
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

    private EdgeRouteStyle resolveRouteStyle(Element edge,
                                             CellInfo source,
                                             CellInfo target,
                                             List<CellInfo> cells,
                                             boolean horizontal,
                                             boolean forward) {
        boolean auxiliary = isAuxiliaryEdge(edge);
        List<CellInfo> relatedEdges = cells.stream()
                .filter(cell -> "edge".equals(cell.getKind()))
                .filter(cell -> sameEndpointPair(cell, source, target))
                .sorted(java.util.Comparator.comparing(CellInfo::getId))
                .toList();
        if (relatedEdges.size() < 2) {
            return alignedRouteStyle(source, target, horizontal, 0.5D, 0.5D, auxiliary);
        }

        String edgeId = StringUtils.defaultString(edge.attributeValue("id"));
        int index = 0;
        for (int i = 0; i < relatedEdges.size(); i++) {
            if (StringUtils.equals(edgeId, relatedEdges.get(i).getId())) {
                index = i;
                break;
            }
        }
        boolean hasOppositeDirection = relatedEdges.stream()
                .anyMatch(related -> StringUtils.equals(related.getSource(), target.getId())
                        && StringUtils.equals(related.getTarget(), source.getId()));
        double trackFraction = hasOppositeDirection && relatedEdges.size() == 2
                ? (forward ? 0.3D : 0.7D)
                : distributedTrack(index, relatedEdges.size());
        double sourceTrackFraction = nodeSideTrack(edge, source, target, true, cells, trackFraction);
        double targetTrackFraction = nodeSideTrack(edge, target, source, false, cells, trackFraction);
        return alignedRouteStyle(source, target, horizontal, sourceTrackFraction, targetTrackFraction, auxiliary);
    }

    private EdgeRouteStyle alignedRouteStyle(CellInfo source,
                                             CellInfo target,
                                             boolean horizontal,
                                             double sourceTrackFraction,
                                             double targetTrackFraction,
                                             boolean auxiliary) {
        if (horizontal) {
            Double laneY = sharedSafeLane(source.getY(), source.getHeight(), sourceTrackFraction,
                    target.getY(), target.getHeight());
            if (laneY != null) {
                return new EdgeRouteStyle(
                        trackFractionForLane(laneY, source.getY(), source.getHeight()),
                        trackFractionForLane(laneY, target.getY(), target.getHeight()),
                        auxiliary);
            }
        } else {
            Double laneX = sharedSafeLane(source.getX(), source.getWidth(), sourceTrackFraction,
                    target.getX(), target.getWidth());
            if (laneX != null) {
                return new EdgeRouteStyle(
                        trackFractionForLane(laneX, source.getX(), source.getWidth()),
                        trackFractionForLane(laneX, target.getX(), target.getWidth()),
                        auxiliary);
            }
        }
        return new EdgeRouteStyle(safePortTrack(sourceTrackFraction), safePortTrack(targetTrackFraction), auxiliary);
    }

    private Double sharedSafeLane(double sourceStart,
                                  double sourceSize,
                                  double sourceTrackFraction,
                                  double targetStart,
                                  double targetSize) {
        if (sourceSize <= 0D || targetSize <= 0D) {
            return null;
        }
        double min = Math.max(sourceStart + sourceSize * PORT_SAFE_MIN, targetStart + targetSize * PORT_SAFE_MIN);
        double max = Math.min(sourceStart + sourceSize * PORT_SAFE_MAX, targetStart + targetSize * PORT_SAFE_MAX);
        if (min > max) {
            return null;
        }
        // Preserve the source-side route role, but snap both endpoints to one pixel lane.
        double preferred = sourceStart + sourceSize * safePortTrack(sourceTrackFraction);
        return clamp(preferred, min, max);
    }

    private double trackFractionForLane(double lane, double start, double size) {
        if (size <= 0D) {
            return 0.5D;
        }
        return safePortTrack((lane - start) / size);
    }

    private double safePortTrack(double trackFraction) {
        return clamp(trackFraction, PORT_SAFE_MIN, PORT_SAFE_MAX);
    }

    private double nodeSideTrack(Element currentEdge,
                                 CellInfo endpoint,
                                 CellInfo otherEndpoint,
                                 boolean sourceEndpoint,
                                 List<CellInfo> cells,
                                 double fallbackTrackFraction) {
        Map<String, CellInfo> nodesById = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CellInfo::getId, cell -> cell, (left, right) -> left));
        NodeSide side = nodeSideFacing(endpoint, otherEndpoint);
        List<EndpointBinding> bindings = new ArrayList<>();
        for (CellInfo edge : cells) {
            if (!"edge".equals(edge.getKind())) {
                continue;
            }
            addEndpointBinding(bindings, edge, nodesById, endpoint, side, true);
            addEndpointBinding(bindings, edge, nodesById, endpoint, side, false);
        }
        if (bindings.size() <= 2) {
            return fallbackTrackFraction;
        }

        bindings.sort(java.util.Comparator
                .comparingDouble(EndpointBinding::sortCoordinate)
                .thenComparing(EndpointBinding::auxiliary)
                .thenComparing(EndpointBinding::edgeId));
        String currentEdgeId = StringUtils.defaultString(currentEdge.attributeValue("id"));
        for (int i = 0; i < bindings.size(); i++) {
            EndpointBinding binding = bindings.get(i);
            if (StringUtils.equals(currentEdgeId, binding.edgeId())
                    && sourceEndpoint == binding.sourceEndpoint()) {
                return distributedNodeSideTrack(i, bindings.size());
            }
        }
        return fallbackTrackFraction;
    }

    private void addEndpointBinding(List<EndpointBinding> bindings,
                                    CellInfo edge,
                                    Map<String, CellInfo> nodesById,
                                    CellInfo endpoint,
                                    NodeSide side,
                                    boolean sourceEndpoint) {
        String endpointId = sourceEndpoint ? edge.getSource() : edge.getTarget();
        if (!StringUtils.equals(endpoint.getId(), endpointId)) {
            return;
        }
        String otherId = sourceEndpoint ? edge.getTarget() : edge.getSource();
        CellInfo other = nodesById.get(otherId);
        if (other == null || StringUtils.equals(endpoint.getId(), other.getId())
                || nodeSideFacing(endpoint, other) != side) {
            return;
        }
        bindings.add(new EndpointBinding(
                edge.getId(),
                sourceEndpoint,
                isAuxiliaryEdge(edge.getStyle(), edge.getLabel()),
                (side == NodeSide.LEFT || side == NodeSide.RIGHT) ? other.centerY() : other.centerX()
        ));
    }

    private NodeSide nodeSideFacing(CellInfo endpoint, CellInfo otherEndpoint) {
        double dx = otherEndpoint.centerX() - endpoint.centerX();
        double dy = otherEndpoint.centerY() - endpoint.centerY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0D ? NodeSide.RIGHT : NodeSide.LEFT;
        }
        return dy >= 0D ? NodeSide.BOTTOM : NodeSide.TOP;
    }

    private double distributedNodeSideTrack(int index, int count) {
        if (count <= 1) {
            return 0.5D;
        }
        return PORT_SAFE_MIN + ((PORT_SAFE_MAX - PORT_SAFE_MIN)
                * Math.max(0, Math.min(index, count - 1)) / (count - 1));
    }

    private boolean sameEndpointPair(CellInfo edge, CellInfo source, CellInfo target) {
        return (StringUtils.equals(edge.getSource(), source.getId()) && StringUtils.equals(edge.getTarget(), target.getId()))
                || (StringUtils.equals(edge.getSource(), target.getId()) && StringUtils.equals(edge.getTarget(), source.getId()));
    }

    private double distributedTrack(int index, int count) {
        if (count <= 1) {
            return 0.5D;
        }
        return PORT_SAFE_MIN + ((PORT_SAFE_MAX - PORT_SAFE_MIN)
                * Math.max(0, Math.min(index, count - 1)) / (count - 1));
    }

    private boolean isAuxiliaryEdge(Element edge) {
        return isAuxiliaryEdge(edge.attributeValue("style"), edge.attributeValue("value"));
    }

    private boolean isAuxiliaryEdge(String rawStyle, String rawLabel) {
        String style = StringUtils.defaultString(rawStyle).toLowerCase(Locale.ROOT);
        String label = cleanLabel(rawLabel).toLowerCase(Locale.ROOT);
        return style.contains("dashed=1")
                || label.contains("return")
                || label.contains("response")
                || label.contains("async")
                || label.contains("callback")
                || label.contains("返回")
                || label.contains("响应")
                || label.contains("异步")
                || label.contains("回调");
    }

    private String ensureStyleTokens(String style,
                                     boolean horizontal,
                                     boolean forward,
                                     double sourceTrackFraction,
                                     double targetTrackFraction,
                                     boolean auxiliary) {
        Map<String, String> tokens = parseStyle(style);
        tokens.put("edgeStyle", "orthogonalEdgeStyle");
        tokens.put("rounded", auxiliary ? "1" : "0");
        tokens.put("orthogonalLoop", "1");
        tokens.put("jettySize", "auto");
        tokens.put("html", "1");
        tokens.putIfAbsent("endArrow", "classic");
        if (auxiliary) {
            tokens.putIfAbsent("arcSize", "10");
            tokens.putIfAbsent("strokeColor", "#64748b");
        } else {
            tokens.putIfAbsent("strokeColor", "#334155");
        }
        String sourceTrack = trimStyleNumber(sourceTrackFraction);
        String targetTrack = trimStyleNumber(targetTrackFraction);
        tokens.put("exitX", horizontal ? (forward ? "1" : "0") : sourceTrack);
        tokens.put("exitY", horizontal ? sourceTrack : (forward ? "1" : "0"));
        tokens.put("entryX", horizontal ? (forward ? "0" : "1") : targetTrack);
        tokens.put("entryY", horizontal ? targetTrack : (forward ? "0" : "1"));
        return tokens.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(";")) + ";";
    }

    private String trimStyleNumber(double value) {
        String raw = trimNumber(value);
        if (!raw.contains(".")) {
            return raw;
        }
        while (raw.endsWith("0")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : raw;
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

    private String sanitizeValueAttributes(String xml) {
        // Model-generated labels often use raw JVM notation like <heap>; keep it as text.
        Matcher matcher = VALUE_ATTRIBUTE_PATTERN.matcher(xml);
        StringBuffer sanitized = new StringBuffer(xml.length());
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + matcher.group(2)
                    + escapeLabelValue(matcher.group(3))
                    + matcher.group(2);
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private String escapeLabelValue(String label) {
        StringBuilder escaped = new StringBuilder(label.length());
        for (int index = 0; index < label.length(); index++) {
            char current = label.charAt(index);
            if (current == '&' && isEntityReference(label, index)) {
                int semicolon = label.indexOf(';', index);
                escaped.append(label, index, semicolon + 1);
                index = semicolon;
                continue;
            }
            if (current == '&') {
                escaped.append("&amp;");
            } else if (current == '<') {
                escaped.append("&lt;");
            } else if (current == '>') {
                escaped.append("&gt;");
            } else {
                escaped.append(current);
            }
        }
        return escaped.toString();
    }

    private boolean isEntityReference(String text, int ampersandIndex) {
        int semicolon = text.indexOf(';', ampersandIndex);
        if (semicolon < 0 || semicolon - ampersandIndex > 12) {
            return false;
        }
        String entity = text.substring(ampersandIndex + 1, semicolon);
        return entity.matches("#[0-9]+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos");
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

        double trackX(double fraction) {
            return x + width * fraction;
        }

        double trackY(double fraction) {
            return y + height * fraction;
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

    private static class EdgeRouteStyle {
        private final double sourceTrackFraction;
        private final double targetTrackFraction;
        private final boolean auxiliary;

        private EdgeRouteStyle(double trackFraction, boolean auxiliary) {
            this(trackFraction, trackFraction, auxiliary);
        }

        private EdgeRouteStyle(double sourceTrackFraction, double targetTrackFraction, boolean auxiliary) {
            this.sourceTrackFraction = sourceTrackFraction;
            this.targetTrackFraction = targetTrackFraction;
            this.auxiliary = auxiliary;
        }

        private double getSourceTrackFraction() {
            return sourceTrackFraction;
        }

        private double getTargetTrackFraction() {
            return targetTrackFraction;
        }

        private boolean isAuxiliary() {
            return auxiliary;
        }
    }

    private enum NodeSide {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    private record EndpointBinding(String edgeId,
                                   boolean sourceEndpoint,
                                   boolean auxiliary,
                                   double sortCoordinate) {
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
