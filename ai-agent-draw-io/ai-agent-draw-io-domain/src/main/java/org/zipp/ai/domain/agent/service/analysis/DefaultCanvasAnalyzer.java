package org.zipp.ai.domain.agent.service.analysis;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisRequest;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasEvidenceSource;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueCategory;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueEvidence;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasPointData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasQualityIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasRepairability;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramQualityProfile;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.analysis.LayoutFamily;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DefaultCanvasAnalyzer implements ICanvasAnalyzer {

    private static final double OVERLAP_TOLERANCE = 8D;
    private static final double GEOMETRY_EPSILON = 0.0001D;
    private static final double PORT_CROWDING_TRACK_TOLERANCE = 0.08D;
    private static final double PORT_SAFE_MIN = 0.25D;
    private static final double PORT_SAFE_MAX = 0.75D;
    private static final double MIN_LABEL_LINE_OFFSET = 30D;
    private final DiagramQualityProfileCatalog profileCatalog = new DiagramQualityProfileCatalog();

    @Override
    public CanvasAnalysis analyze(String mxGraphModelXml, String diagramType) {
        // Temporary caller adapter: the typed request is the production implementation path.
        return analyze(new CanvasAnalysisRequest(mxGraphModelXml, DiagramType.from(diagramType), null, null));
    }

    @Override
    public CanvasAnalysis analyze(CanvasAnalysisRequest request) {
        if (request == null) {
            DiagramQualityProfile profile = profileCatalog.resolve(DiagramType.GENERIC);
            return invalid("No canvas analysis request was provided.", DiagramType.GENERIC,
                    profile.defaultLayoutFamily(), profile);
        }
        DiagramQualityProfile profile = profileCatalog.resolve(request.diagramType(), request.profileVersion());
        return analyzeInternal(request.mxGraphModelXml(), request.layoutHint(), profile);
    }

    private CanvasAnalysis analyzeInternal(String mxGraphModelXml,
                                           LayoutFamily layoutHint,
                                           DiagramQualityProfile profile) {
        if (StringUtils.isBlank(mxGraphModelXml)) {
            return invalid("No Draw.io XML was provided.", profile.diagramType(),
                    resolveLayoutFamily(profile, layoutHint, "grid"), profile);
        }

        try {
            Document document = DocumentHelper.parseText(toGraphModel(mxGraphModelXml));
            Element root = document.getRootElement().element("root");
            if (root == null) {
                return invalid("mxGraphModel is missing a root element.", profile.diagramType(),
                        resolveLayoutFamily(profile, layoutHint, "grid"), profile);
            }

            List<CanvasCellData> cells = readCells(root);
            normalizeAbsoluteCoordinates(cells);
            List<CanvasAnalysisIssue> issues = analyzeIssues(cells).stream()
                    .filter(issue -> profile.enables(issue.getType()))
                    .toList();
            String layoutMode = resolveLayoutMode(cells);
            LayoutFamily layoutFamily = resolveLayoutFamily(profile, layoutHint, layoutMode);
            return CanvasAnalysis.builder()
                    .valid(!hasBlockingIssue(issues))
                    .severity(resolveSeverity(issues))
                    .layoutMode(layoutMode)
                    .diagramType(profile.diagramType())
                    .layoutFamily(layoutFamily)
                    .profileVersion(profile.version())
                    .issues(issues)
                    .qualityIssues(toQualityIssues(issues, profile))
                    .cells(cells)
                    .summary(summary(cells))
                    .build();
        } catch (Exception e) {
            return invalid("The Draw.io XML could not be parsed: " + e.getMessage(), profile.diagramType(),
                    resolveLayoutFamily(profile, layoutHint, "grid"), profile);
        }
    }

    private CanvasAnalysis invalid(String message,
                                   DiagramType diagramType,
                                   LayoutFamily layoutFamily,
                                   DiagramQualityProfile profile) {
        CanvasAnalysisIssue legacyIssue = issue(
                CanvasIssueType.INVALID_XML,
                "structure",
                "critical",
                List.of(),
                message,
                "none"
        );
        return CanvasAnalysis.builder()
                .valid(false)
                .severity("critical")
                .layoutMode("grid")
                .diagramType(diagramType)
                .layoutFamily(layoutFamily)
                .profileVersion(profile.version())
                .issues(List.of(legacyIssue))
                .qualityIssues(toQualityIssues(List.of(legacyIssue), profile))
                .cells(List.of())
                .summary(CanvasSummaryData.builder()
                        .nodeCount(0)
                        .edgeCount(0)
                        .summary("Canvas analysis failed.")
                        .build())
                .build();
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
        if (isFreeformIllustration(cells)) {
            // Freeform art (mascots, scenes) is built from intentionally overlapping,
            // unlabeled shapes; diagram layout heuristics only produce destructive
            // "repairs" here, so structural validity is the whole contract.
            return issues;
        }
        // Ellipse zone backgrounds (rings, halos) legitimately sit under nodes and edges;
        // computing them once keeps overlap/crossing checks honest for radial layouts.
        Set<String> zoneIds = detectZoneCells(cells);
        detectNodeOverlaps(cells, zoneIds, issues);
        detectEdgeNodeCrossings(cells, zoneIds, issues);
        detectPortDirectionMismatches(cells, issues);
        detectPortCornerProximity(cells, issues);
        detectParallelEdgeTrackOverlaps(cells, issues);
        detectNodeSidePortCrowding(cells, issues);
        detectRemovableWaypoints(cells, zoneIds, issues);
        detectOpaqueTextBackgrounds(cells, issues);
        // Perceptual quality that is computable from geometry alone (no rendering needed).
        detectTextOverflow(cells, issues);
        detectOversizedRegions(cells, issues);
        detectPaletteIncoherence(cells, issues);
        detectUnevenSpacing(cells, zoneIds, issues);
        detectEdgeLabelCollisions(cells, zoneIds, issues);
        return issues;
    }

    /**
     * Zone cells are large ellipse backgrounds (onion rings, ecosystem boundaries, cycle
     * halos) that other nodes intentionally sit on top of. An ellipse qualifies when it
     * fully contains at least one much smaller non-text node — grid nodes never contain
     * each other, so this never masks an accidental node-on-node overlap.
     */
    private Set<String> detectZoneCells(List<CanvasCellData> cells) {
        List<CanvasCellData> nodes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell))
                .toList();
        Set<String> zoneIds = new HashSet<>();
        for (CanvasCellData candidate : nodes) {
            String style = StringUtils.defaultString(candidate.getStyle()).toLowerCase(Locale.ROOT);
            if (!style.contains("ellipse")) {
                continue;
            }
            double candidateArea = candidate.getWidth() * candidate.getHeight();
            for (CanvasCellData other : nodes) {
                if (other == candidate || StringUtils.equals(other.getId(), candidate.getId())) {
                    continue;
                }
                boolean contains = other.getX() >= candidate.getX() && other.getY() >= candidate.getY()
                        && other.maxX() <= candidate.maxX() && other.maxY() <= candidate.maxY();
                if (contains && candidateArea >= 3D * other.getWidth() * other.getHeight()) {
                    zoneIds.add(candidate.getId());
                    break;
                }
            }
        }
        return zoneIds;
    }

    /**
     * Free-routed edges opt out of orthogonal routing explicitly (radial spokes, cycle arcs,
     * curved secondary flows). They are never auto-rerouted onto orthogonal tracks — issues on
     * them go back to the model as candidates instead.
     */
    private boolean isFreeRoutedEdge(CanvasCellData edge) {
        String style = StringUtils.defaultString(edge.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("edgestyle=none") || style.contains("curved=1");
    }

    /**
     * Grid diagrams read along rows/columns; radial diagrams read from a center outwards.
     * Radial is recognized by its structural signature: ellipse zone backgrounds, or a
     * majority of deliberately free-routed connected edges.
     */
    private String resolveLayoutMode(List<CanvasCellData> cells) {
        if (isFreeformIllustration(cells)) {
            return "illustration";
        }
        if (!detectZoneCells(cells).isEmpty()) {
            return "radial";
        }
        List<CanvasCellData> connectedEdges = cells.stream()
                .filter(cell -> "edge".equals(cell.getKind()))
                .filter(edge -> StringUtils.isNotBlank(edge.getSource()) && StringUtils.isNotBlank(edge.getTarget()))
                .toList();
        long freeRouted = connectedEdges.stream().filter(this::isFreeRoutedEdge).count();
        return connectedEdges.size() >= 3 && freeRouted * 2 > connectedEdges.size() ? "radial" : "grid";
    }

    /**
     * A canvas is treated as a freeform illustration when its shapes are mostly
     * unlabeled and nothing is wired together through source/target edges. Diagrams
     * (flowcharts, UML, ER, ...) always label their nodes and connect them; drawings
     * compose bare shapes — with at most a minority of annotation labels — and,
     * at most, standalone point-anchored curves.
     */
    private boolean isFreeformIllustration(List<CanvasCellData> cells) {
        List<CanvasCellData> shapes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> !isTextCell(cell))
                .toList();
        if (shapes.size() < 4) {
            return false;
        }
        boolean hasConnectedEdge = cells.stream()
                .filter(cell -> "edge".equals(cell.getKind()))
                .anyMatch(edge -> StringUtils.isNotBlank(edge.getSource()) || StringUtils.isNotBlank(edge.getTarget()));
        if (hasConnectedEdge) {
            return false;
        }
        long labeledShapes = shapes.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getLabel()))
                .count();
        // Up to 40% annotation labels still reads as a sketch; a fully labeled but
        // unconnected layout (e.g. a kanban of boxes) keeps the diagram heuristics.
        return labeledShapes <= Math.max(1, shapes.size() * 2 / 5);
    }

    private void detectPortDirectionMismatches(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));

        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind())) {
                continue;
            }
            if (edge.getPoints() != null && !edge.getPoints().isEmpty()) {
                continue;
            }
            CanvasCellData source = cellsById.get(edge.getSource());
            CanvasCellData target = cellsById.get(edge.getTarget());
            if (source == null || target == null || StringUtils.equals(source.getId(), target.getId())) {
                continue;
            }
            // Sequence lifelines use center-line message ports by design; ordinary side-port
            // direction rules would incorrectly mark valid sequence messages as backwards.
            if (isUmlLifeline(source) || isUmlLifeline(target)) {
                continue;
            }
            PortSet ports = readPorts(edge.getStyle());
            if (!ports.complete()) {
                continue;
            }

            double dx = target.centerX() - source.centerX();
            double dy = target.centerY() - source.centerY();
            if (Math.abs(dx) < GEOMETRY_EPSILON && Math.abs(dy) < GEOMETRY_EPSILON) {
                continue;
            }

            boolean horizontal = Math.abs(dx) >= Math.abs(dy);
            boolean forward = horizontal ? dx >= 0D : dy >= 0D;
            boolean matches = horizontal
                    ? near(ports.exitX(), forward ? 1D : 0D) && near(ports.entryX(), forward ? 0D : 1D)
                    : near(ports.exitY(), forward ? 1D : 0D) && near(ports.entryY(), forward ? 0D : 1D);
            if (!matches) {
                issues.add(issue(CanvasIssueType.PORT_DIRECTION_MISMATCH, "geometry", "major", List.of(edge.getId()),
                        "Edge " + edge.getId() + " exits or enters from the side opposite to its visual flow.",
                        "auto_reroute"));
            }
        }
    }

    private void detectPortCornerProximity(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));

        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind()) || StringUtils.isBlank(edge.getSource()) || StringUtils.isBlank(edge.getTarget())) {
                continue;
            }
            CanvasCellData source = cellsById.get(edge.getSource());
            CanvasCellData target = cellsById.get(edge.getTarget());
            if (source == null || target == null) {
                continue;
            }
            PortSet ports = readPorts(edge.getStyle());
            if (!ports.complete()) {
                continue;
            }

            addCornerProximityIssue(edge, source, ports.exitX(), ports.exitY(), issues);
            addCornerProximityIssue(edge, target, ports.entryX(), ports.entryY(), issues);
        }
    }

    private void addCornerProximityIssue(CanvasCellData edge,
                                         CanvasCellData node,
                                         Double xFraction,
                                         Double yFraction,
                                         List<CanvasAnalysisIssue> issues) {
        if (!isRoundedNode(node)) {
            return;
        }
        PortSide side = portSide(xFraction, yFraction);
        if (side == null) {
            return;
        }
        Double track = side.track(xFraction, yFraction);
        if (track == null || (track >= PORT_SAFE_MIN && track <= PORT_SAFE_MAX)) {
            return;
        }
        issues.add(issue(CanvasIssueType.PORT_CORNER_PROXIMITY, "geometry", "major",
                List.of(edge.getId(), node.getId()),
                "Edge " + edge.getId() + " attaches too close to a rounded corner of node " + node.getId()
                        + "; move the side port into the safe middle band.",
                "auto_reroute"));
    }

    private void detectParallelEdgeTrackOverlaps(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        Map<String, List<CanvasCellData>> edgesByPair = new HashMap<>();
        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind()) || StringUtils.isBlank(edge.getSource()) || StringUtils.isBlank(edge.getTarget())) {
                continue;
            }
            if (!cellsById.containsKey(edge.getSource()) || !cellsById.containsKey(edge.getTarget())) {
                continue;
            }
            edgesByPair.computeIfAbsent(unorderedEndpointKey(edge), ignored -> new ArrayList<>()).add(edge);
        }

        for (List<CanvasCellData> relatedEdges : edgesByPair.values()) {
            if (relatedEdges.size() < 2) {
                continue;
            }
            for (int i = 0; i < relatedEdges.size(); i++) {
                CanvasCellData left = relatedEdges.get(i);
                CanvasCellData leftSource = cellsById.get(left.getSource());
                CanvasCellData leftTarget = cellsById.get(left.getTarget());
                Double leftTrack = renderedTrack(left, leftSource, leftTarget);
                if (leftTrack == null) {
                    continue;
                }
                for (int j = i + 1; j < relatedEdges.size(); j++) {
                    CanvasCellData right = relatedEdges.get(j);
                    CanvasCellData rightSource = cellsById.get(right.getSource());
                    CanvasCellData rightTarget = cellsById.get(right.getTarget());
                    Double rightTrack = renderedTrack(right, rightSource, rightTarget);
                    if (rightTrack == null) {
                        continue;
                    }
                    if (Math.abs(leftTrack - rightTrack) <= 8D) {
                        issues.add(issue(CanvasIssueType.PARALLEL_EDGE_OVERLAP, "geometry", "major",
                                List.of(left.getId(), right.getId()),
                                "Edges " + left.getId() + " and " + right.getId()
                                        + " share the same visual track; separate request/return or parallel paths.",
                                "auto_reroute"));
                    }
                }
            }
        }
    }

    private Double renderedTrack(CanvasCellData edge, CanvasCellData source, CanvasCellData target) {
        if (source == null || target == null) {
            return null;
        }
        List<CanvasPointData> route = reconstructRoute(edge, source, target);
        if (route == null || route.size() < 2) {
            return null;
        }
        boolean horizontal = Math.abs(target.centerX() - source.centerX()) >= Math.abs(target.centerY() - source.centerY());
        CanvasPointData first = route.get(0);
        CanvasPointData last = route.get(route.size() - 1);
        return horizontal ? (first.getY() + last.getY()) / 2D : (first.getX() + last.getX()) / 2D;
    }

    private void detectNodeSidePortCrowding(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> nodesById = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        Map<String, List<EndpointPortBinding>> bindingsByNodeSide = new HashMap<>();

        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind()) || StringUtils.isBlank(edge.getSource()) || StringUtils.isBlank(edge.getTarget())) {
                continue;
            }
            CanvasCellData source = nodesById.get(edge.getSource());
            CanvasCellData target = nodesById.get(edge.getTarget());
            if (source == null || target == null) {
                continue;
            }
            PortSet ports = readPorts(edge.getStyle());
            if (!ports.complete()) {
                continue;
            }

            addEndpointPortBinding(bindingsByNodeSide, edge.getId(), source.getId(), ports.exitX(), ports.exitY());
            addEndpointPortBinding(bindingsByNodeSide, edge.getId(), target.getId(), ports.entryX(), ports.entryY());
        }

        for (List<EndpointPortBinding> bindings : bindingsByNodeSide.values()) {
            if (bindings.size() < 2) {
                continue;
            }
            List<EndpointPortBinding> sorted = bindings.stream()
                    .sorted(Comparator.comparingDouble(EndpointPortBinding::track)
                            .thenComparing(EndpointPortBinding::edgeId))
                    .toList();
            List<String> crowdedEdgeIds = new ArrayList<>();
            for (int i = 0; i + 1 < sorted.size(); i++) {
                EndpointPortBinding left = sorted.get(i);
                EndpointPortBinding right = sorted.get(i + 1);
                if (Math.abs(left.track() - right.track()) <= PORT_CROWDING_TRACK_TOLERANCE) {
                    addUnique(crowdedEdgeIds, left.edgeId());
                    addUnique(crowdedEdgeIds, right.edgeId());
                }
            }
            if (!crowdedEdgeIds.isEmpty()) {
                EndpointPortBinding first = sorted.get(0);
                issues.add(issue(CanvasIssueType.NODE_SIDE_PORT_CROWDING, "geometry", "major",
                        crowdedEdgeIds,
                        "Node " + first.nodeId() + " has multiple edges stacked on its "
                                + first.side().label() + " port tracks; spread same-side connector ports.",
                        "auto_reroute"));
            }
        }
    }

    private void addEndpointPortBinding(Map<String, List<EndpointPortBinding>> bindingsByNodeSide,
                                        String edgeId,
                                        String nodeId,
                                        Double xFraction,
                                        Double yFraction) {
        PortSide side = portSide(xFraction, yFraction);
        if (side == null) {
            return;
        }
        Double track = side.track(xFraction, yFraction);
        if (track == null) {
            return;
        }
        // The track is the normalized position along a side; duplicate tracks stack arrowheads.
        bindingsByNodeSide
                .computeIfAbsent(nodeId + "::" + side.name(), ignored -> new ArrayList<>())
                .add(new EndpointPortBinding(edgeId, nodeId, side, track));
    }

    private PortSide portSide(Double xFraction, Double yFraction) {
        if (near(xFraction, 0D)) {
            return PortSide.LEFT;
        }
        if (near(xFraction, 1D)) {
            return PortSide.RIGHT;
        }
        if (near(yFraction, 0D)) {
            return PortSide.TOP;
        }
        if (near(yFraction, 1D)) {
            return PortSide.BOTTOM;
        }
        return null;
    }

    private void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private String unorderedEndpointKey(CanvasCellData edge) {
        String source = StringUtils.defaultString(edge.getSource());
        String target = StringUtils.defaultString(edge.getTarget());
        return source.compareTo(target) <= 0 ? source + "::" + target : target + "::" + source;
    }

    private PortSet readPorts(String style) {
        return new PortSet(
                styleFraction(style, "exitX"),
                styleFraction(style, "exitY"),
                styleFraction(style, "entryX"),
                styleFraction(style, "entryY")
        );
    }

    private boolean near(Double value, double expected) {
        return value != null && Math.abs(value - expected) <= 0.12D;
    }

    /**
     * Draw.io renders an edge label at the midpoint of its path; estimate that box and flag
     * labels that land on a node body or on another edge's label. Repairable deterministically
     * by the route-only optimizer, which re-scores label positions.
     */
    private void detectEdgeLabelCollisions(List<CanvasCellData> cells, Set<String> zoneIds, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        List<CanvasCellData> obstacles = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell) && !isBoundaryLike(cell) && !zoneIds.contains(cell.getId()))
                .toList();

        List<CanvasCellData> labelBoxes = new ArrayList<>();
        List<CanvasCellData> labelEdges = new ArrayList<>();
        for (CanvasCellData edge : cells) {
            if (!"edge".equals(edge.getKind()) || StringUtils.isBlank(edge.getLabel())) {
                continue;
            }
            CanvasCellData source = cellsById.get(edge.getSource());
            CanvasCellData target = cellsById.get(edge.getTarget());
            if (source == null || target == null) {
                continue;
            }
            List<CanvasPointData> route = reconstructRoute(edge, source, target);
            if (route == null || route.size() < 2) {
                continue;
            }
            String repairability = isFreeRoutedEdge(edge) ? "candidate" : "auto_reroute";
            if (Math.abs(edge.getY()) < MIN_LABEL_LINE_OFFSET) {
                issues.add(issue(CanvasIssueType.EDGE_LABEL_COLLISION, "readability", "minor",
                        List.of(edge.getId()),
                        "Label of edge " + edge.getId() + " sits too close to the edge line; move it above or below the line.",
                        repairability));
            }
            CanvasPointData mid = midpointByLength(route);
            double fontSize = fontSize(edge.getStyle());
            double charFactor = cjkRatio(edge.getLabel()) > 0.3D ? CJK_CHAR_WIDTH_FACTOR : LATIN_CHAR_WIDTH_FACTOR;
            double width = clamp(edge.getLabel().length() * fontSize * charFactor, 36D, 200D);
            double height = fontSize * LINE_HEIGHT_FACTOR;
            CanvasCellData labelBox = CanvasCellData.builder()
                    .id(edge.getId())
                    .x(mid.getX() - width / 2D)
                    .y(mid.getY() - height / 2D)
                    .width(width)
                    .height(height)
                    .build();

            for (CanvasCellData node : obstacles) {
                if (isCrossingEndpointOrContainer(edge, node)) {
                    continue;
                }
                if (rectsOverlap(labelBox, node)) {
                    issues.add(issue(CanvasIssueType.EDGE_LABEL_COLLISION, "readability", "major",
                            List.of(edge.getId(), node.getId()),
                            "Label of edge " + edge.getId() + " likely sits on node " + node.getId() + ".",
                            repairability));
                    break;
                }
            }
            for (int i = 0; i < labelBoxes.size(); i++) {
                if (rectsOverlap(labelBox, labelBoxes.get(i))) {
                    issues.add(issue(CanvasIssueType.EDGE_LABEL_COLLISION, "readability", "major",
                            List.of(edge.getId(), labelEdges.get(i).getId()),
                            "Labels of edges " + edge.getId() + " and " + labelEdges.get(i).getId()
                                    + " likely overlap each other.", repairability));
                    break;
                }
            }
            labelBoxes.add(labelBox);
            labelEdges.add(edge);
        }
    }

    private CanvasPointData midpointByLength(List<CanvasPointData> route) {
        double total = 0D;
        for (int i = 0; i + 1 < route.size(); i++) {
            total += distance(route.get(i), route.get(i + 1));
        }
        double remaining = total / 2D;
        for (int i = 0; i + 1 < route.size(); i++) {
            double segment = distance(route.get(i), route.get(i + 1));
            if (segment >= remaining && segment > 0D) {
                double ratio = remaining / segment;
                return CanvasPointData.builder()
                        .x(route.get(i).getX() + (route.get(i + 1).getX() - route.get(i).getX()) * ratio)
                        .y(route.get(i).getY() + (route.get(i + 1).getY() - route.get(i).getY()) * ratio)
                        .build();
            }
            remaining -= segment;
        }
        return route.get(route.size() / 2);
    }

    private double distance(CanvasPointData a, CanvasPointData b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean rectsOverlap(CanvasCellData left, CanvasCellData right) {
        double width = Math.min(left.maxX(), right.maxX()) - Math.max(left.getX(), right.getX());
        double height = Math.min(left.maxY(), right.maxY()) - Math.max(left.getY(), right.getY());
        return width > 0D && height > 0D;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- Static visual-quality heuristics (adapted from drawio-diagram-builder-skill's
    // pre-flight checker). Thresholds are deliberately conservative: majors must be
    // near-certain defects because they drive the self-repair loop.

    private static final double LATIN_CHAR_WIDTH_FACTOR = 0.55D;
    private static final double CJK_CHAR_WIDTH_FACTOR = 0.9D;
    private static final double LINE_HEIGHT_FACTOR = 1.35D;
    private static final double TEXT_OVERFLOW_RATIO = 1.6D;
    private static final double REGION_UNUSED_MIN_PX = 220D;
    private static final int MAX_FILL_COLORS = 6;
    private static final double SPACING_CV_LIMIT = 0.6D;

    /** Estimate wrapped label height against the shape box; flag near-certain overflow. */
    private void detectTextOverflow(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        for (CanvasCellData cell : cells) {
            if (!"node".equals(cell.getKind()) || cell.getWidth() <= 0 || cell.getHeight() <= 0
                    || StringUtils.isBlank(cell.getLabel())) {
                continue;
            }
            String label = cell.getLabel();
            double fontSize = fontSize(cell.getStyle());
            double charFactor = cjkRatio(label) > 0.3D ? CJK_CHAR_WIDTH_FACTOR : LATIN_CHAR_WIDTH_FACTOR;
            double textWidth = label.length() * fontSize * charFactor;
            double usableWidth = Math.max(cell.getWidth() - 10D, 1D);
            double lines = Math.ceil(textWidth / usableWidth);
            double estimatedHeight = lines * fontSize * LINE_HEIGHT_FACTOR;
            if (estimatedHeight > cell.getHeight() * TEXT_OVERFLOW_RATIO) {
                issues.add(issue(CanvasIssueType.TEXT_OVERFLOW, "readability", "major", List.of(cell.getId()),
                        "Label of " + cell.getId() + " likely overflows its box (~" + Math.round(estimatedHeight)
                                + "px of text vs " + Math.round(cell.getHeight()) + "px height).", "candidate"));
            }
        }
    }

    /** A region container much larger than its content reads as an unfinished layout. */
    private void detectOversizedRegions(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Map<String, List<CanvasCellData>> childrenByParent = new HashMap<>();
        for (CanvasCellData cell : cells) {
            if ("node".equals(cell.getKind()) && StringUtils.isNotBlank(cell.getParentId())) {
                childrenByParent.computeIfAbsent(cell.getParentId(), key -> new ArrayList<>()).add(cell);
            }
        }
        for (CanvasCellData region : cells) {
            if (!"node".equals(region.getKind()) || region.getWidth() <= 0 || region.getHeight() <= 0) {
                continue;
            }
            // Lifelines and swimlanes are intentionally long; skip them.
            String style = StringUtils.defaultString(region.getStyle()).toLowerCase(Locale.ROOT);
            if (isUmlLifeline(region) || style.startsWith("swimlane")) {
                continue;
            }
            List<CanvasCellData> children = childrenByParent.get(region.getId());
            if (children == null || children.isEmpty()) {
                continue;
            }
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (CanvasCellData child : children) {
                minX = Math.min(minX, child.getX());
                minY = Math.min(minY, child.getY());
                maxX = Math.max(maxX, child.maxX());
                maxY = Math.max(maxY, child.maxY());
            }
            double contentWidth = Math.max(maxX - minX, 1D);
            double contentHeight = Math.max(maxY - minY, 1D);
            double unusedWidth = region.getWidth() - contentWidth;
            double unusedHeight = region.getHeight() - contentHeight;
            boolean widthBloated = unusedWidth > REGION_UNUSED_MIN_PX && unusedWidth > 1.2D * contentWidth;
            boolean heightBloated = unusedHeight > REGION_UNUSED_MIN_PX && unusedHeight > 1.2D * contentHeight;
            if (widthBloated || heightBloated) {
                issues.add(issue(CanvasIssueType.OVERSIZED_REGION, "layout", "major", List.of(region.getId()),
                        "Region " + region.getId() + " is much larger than its content ("
                                + Math.round(region.getWidth()) + "x" + Math.round(region.getHeight())
                                + " vs content " + Math.round(contentWidth) + "x" + Math.round(contentHeight) + ").",
                        "candidate"));
            }
        }
    }

    /** More than a handful of fill colors reads as noise instead of semantics. */
    private void detectPaletteIncoherence(List<CanvasCellData> cells, List<CanvasAnalysisIssue> issues) {
        Set<String> fills = new HashSet<>();
        for (CanvasCellData cell : cells) {
            if (!"node".equals(cell.getKind()) || isTextCell(cell)) {
                continue;
            }
            String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
            int index = style.indexOf("fillcolor=#");
            if (index < 0) {
                continue;
            }
            String hex = style.substring(index + "fillcolor=".length(),
                    Math.min(style.length(), index + "fillcolor=".length() + 7));
            if (!"#ffffff".equals(hex)) {
                fills.add(hex);
            }
        }
        if (fills.size() > MAX_FILL_COLORS) {
            issues.add(issue(CanvasIssueType.PALETTE_INCOHERENT, "style", "minor", List.of(),
                    "Diagram uses " + fills.size() + " distinct fill colors; consolidate to at most "
                            + MAX_FILL_COLORS + " semantic color roles.", "candidate"));
        }
    }

    /** Same-parent rows/columns with wildly irregular gaps lack visual rhythm. */
    private void detectUnevenSpacing(List<CanvasCellData> cells, Set<String> zoneIds, List<CanvasAnalysisIssue> issues) {
        List<CanvasCellData> nodes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell) && !isBoundaryLike(cell) && !zoneIds.contains(cell.getId()))
                .toList();
        Map<String, List<CanvasCellData>> byParent = new HashMap<>();
        for (CanvasCellData node : nodes) {
            byParent.computeIfAbsent(StringUtils.defaultString(node.getParentId()), key -> new ArrayList<>()).add(node);
        }
        for (List<CanvasCellData> siblings : byParent.values()) {
            checkAxisRhythm(siblings, true, issues);
            checkAxisRhythm(siblings, false, issues);
        }
    }

    private void checkAxisRhythm(List<CanvasCellData> siblings, boolean horizontal, List<CanvasAnalysisIssue> issues) {
        // Group siblings sharing the same cross-axis coordinate (a visual row or column).
        Map<Long, List<CanvasCellData>> groups = new HashMap<>();
        for (CanvasCellData node : siblings) {
            long key = Math.round((horizontal ? node.getY() : node.getX()) / 10D);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
        for (List<CanvasCellData> group : groups.values()) {
            if (group.size() < 3) {
                continue;
            }
            List<CanvasCellData> sorted = group.stream()
                    .sorted(Comparator.comparingDouble(cell -> horizontal ? cell.getX() : cell.getY()))
                    .toList();
            List<Double> gaps = new ArrayList<>();
            for (int i = 0; i + 1 < sorted.size(); i++) {
                double gap = horizontal
                        ? sorted.get(i + 1).getX() - sorted.get(i).maxX()
                        : sorted.get(i + 1).getY() - sorted.get(i).maxY();
                gaps.add(Math.max(gap, 0D));
            }
            double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
            if (mean <= 0D) {
                continue;
            }
            double variance = gaps.stream().mapToDouble(gap -> (gap - mean) * (gap - mean)).average().orElse(0D);
            double coefficientOfVariation = Math.sqrt(variance) / mean;
            if (coefficientOfVariation > SPACING_CV_LIMIT) {
                issues.add(issue(CanvasIssueType.UNEVEN_SPACING, "layout", "minor",
                        sorted.stream().map(CanvasCellData::getId).toList(),
                        (horizontal ? "Row" : "Column") + " of " + sorted.size()
                                + " nodes has irregular gaps; align them to one uniform spacing.", "candidate"));
            }
        }
    }

    private double fontSize(String style) {
        String normalized = StringUtils.defaultString(style);
        int index = normalized.indexOf("fontSize=");
        if (index < 0) {
            return 12D;
        }
        int start = index + "fontSize=".length();
        int end = start;
        while (end < normalized.length() && (Character.isDigit(normalized.charAt(end)) || normalized.charAt(end) == '.')) {
            end++;
        }
        try {
            return Double.parseDouble(normalized.substring(start, end));
        } catch (Exception ignored) {
            return 12D;
        }
    }

    private double cjkRatio(String text) {
        if (StringUtils.isBlank(text)) {
            return 0D;
        }
        long cjk = text.chars().filter(ch -> ch >= 0x2E80).count();
        return (double) cjk / text.length();
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
        // Standalone lines (legend samples, annotations) legitimately carry no source/target
        // and are anchored by sourcePoint/targetPoint geometry instead.
        if (StringUtils.isBlank(edge.getSource()) && StringUtils.isBlank(edge.getTarget())
                && edge.getSourcePoint() == null && edge.getTargetPoint() == null) {
            issues.add(issue(CanvasIssueType.BROKEN_EDGE, "structure", "critical", List.of(edge.getId()),
                    "Edge " + edge.getId() + " has no source/target ids.", "none"));
        }
    }

    private void detectNodeOverlaps(List<CanvasCellData> cells, Set<String> zoneIds, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellById = new HashMap<>();
        for (CanvasCellData cell : cells) {
            if (StringUtils.isNotBlank(cell.getId())) {
                cellById.putIfAbsent(cell.getId(), cell);
            }
        }
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
                if (width > OVERLAP_TOLERANCE && height > OVERLAP_TOLERANCE
                        && !isRelatedByAncestry(left, right, cellById)
                        && !zoneIds.contains(left.getId()) && !zoneIds.contains(right.getId())
                        && !isBoundaryLike(left) && !isBoundaryLike(right)) {
                    issues.add(issue(CanvasIssueType.NODE_OVERLAP, "geometry", "major", List.of(left.getId(), right.getId()),
                            "Overlapping nodes: " + left.getId() + " and " + right.getId(), "candidate"));
                }
            }
        }
    }

    /** True when one cell is an ancestor container of the other, at any nesting depth. */
    private boolean isRelatedByAncestry(CanvasCellData left, CanvasCellData right, Map<String, CanvasCellData> cellById) {
        return isAncestorOf(left, right, cellById) || isAncestorOf(right, left, cellById);
    }

    private boolean isAncestorOf(CanvasCellData ancestor, CanvasCellData descendant, Map<String, CanvasCellData> cellById) {
        String parentId = descendant.getParentId();
        int depth = 0;
        while (StringUtils.isNotBlank(parentId) && depth++ < 32) {
            if (StringUtils.equals(parentId, ancestor.getId())) {
                return true;
            }
            CanvasCellData parent = cellById.get(parentId);
            parentId = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    /**
     * Boundary/region cells are visual backgrounds that intentionally sit under other nodes;
     * flagging them as overlaps floods reviews with false positives.
     */
    private boolean isBoundaryLike(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("container=1")
                || style.startsWith("group")
                || style.startsWith("swimlane")
                || (style.contains("fillcolor=none") && !style.startsWith("text"));
    }

    private void detectEdgeNodeCrossings(List<CanvasCellData> cells, Set<String> zoneIds, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        List<CanvasCellData> nodes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell))
                // Transparent boundaries, containers, and zone backgrounds legitimately have
                // edges running across their body; only solid content nodes are routing obstacles.
                .filter(cell -> !isBoundaryLike(cell) && !zoneIds.contains(cell.getId()))
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
            // Draw.io's orthogonal router does NOT avoid unrelated nodes; when the edge declares
            // explicit exit/entry ports we can reconstruct its probable path and check it.
            // Only port-less auto-routed edges stay unverifiable and are skipped.
            List<CanvasPointData> route = reconstructRoute(edge, source, target);
            if (route == null) {
                continue;
            }
            // Free-routed edges must never be straightened onto orthogonal tracks; hand
            // their crossings back to the model instead of the deterministic rerouter.
            String repairability = isFreeRoutedEdge(edge) ? "candidate" : "auto_reroute";
            for (CanvasCellData node : nodes) {
                if (isCrossingEndpointOrContainer(edge, node) || !routeIntersectsNode(route, node)) {
                    continue;
                }
                issues.add(issue(CanvasIssueType.EDGE_NODE_CROSSING, "geometry", "major", List.of(edge.getId(), node.getId()),
                        "Edge " + edge.getId() + " crosses node body: " + node.getId(), repairability));
            }
        }
    }

    private void detectRemovableWaypoints(List<CanvasCellData> cells, Set<String> zoneIds, List<CanvasAnalysisIssue> issues) {
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, cell -> cell, (left, right) -> left));
        List<CanvasCellData> nodes = cells.stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .filter(cell -> cell.getWidth() > 0 && cell.getHeight() > 0)
                .filter(cell -> !isTextCell(cell))
                .filter(cell -> !isBoundaryLike(cell) && !zoneIds.contains(cell.getId()))
                .toList();
        List<CanvasCellData> edges = cells.stream().filter(cell -> "edge".equals(cell.getKind())).toList();

        for (CanvasCellData edge : edges) {
            if (edge.getPoints() == null || edge.getPoints().isEmpty()) {
                continue;
            }
            // Curved/free edges shape their arc with waypoints on purpose.
            if (isFreeRoutedEdge(edge)) {
                continue;
            }
            CanvasCellData source = cellsById.get(edge.getSource());
            CanvasCellData target = cellsById.get(edge.getTarget());
            if (source == null || target == null) {
                continue;
            }
            // A dogleg may deliberately separate parallel edges onto distinct tracks; leave those alone.
            if (hasParallelEdge(edge, edges)) {
                continue;
            }
            List<CanvasPointData> directRoute = List.of(
                    anchorToward(source, edge.getSourcePoint(), center(target)),
                    anchorToward(target, edge.getTargetPoint(), center(source)));
            boolean directBlocked = nodes.stream()
                    .anyMatch(node -> !isCrossingEndpointOrContainer(edge, node) && routeIntersectsNode(directRoute, node));
            if (!directBlocked) {
                issues.add(issue(CanvasIssueType.REMOVABLE_WAYPOINT, "readability", "minor", List.of(edge.getId()),
                        "Edge " + edge.getId() + " has removable waypoints; a direct route is already clear.", "candidate"));
            }
        }
    }

    private boolean hasParallelEdge(CanvasCellData edge, List<CanvasCellData> edges) {
        for (CanvasCellData other : edges) {
            if (other == edge) {
                continue;
            }
            boolean sameEndpoints = (StringUtils.equals(other.getSource(), edge.getSource())
                    && StringUtils.equals(other.getTarget(), edge.getTarget()))
                    || (StringUtils.equals(other.getSource(), edge.getTarget())
                    && StringUtils.equals(other.getTarget(), edge.getSource()));
            if (sameEndpoints) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best reconstruction of the rendered edge path: explicit waypoints win; otherwise explicit
     * exit/entry ports give exact endpoints (with an L/Z jog for orthogonal styles, a straight
     * segment for plain edges); a port-less straight edge falls back to anchor estimation.
     * Returns null only for port-less auto-routed edges, whose path is genuinely unknowable.
     */
    private List<CanvasPointData> reconstructRoute(CanvasCellData edge, CanvasCellData source, CanvasCellData target) {
        if (edge.getPoints() != null && !edge.getPoints().isEmpty()) {
            return edgeRoute(edge, source, target);
        }
        List<CanvasPointData> portRoute = portBasedRoute(edge, source, target);
        if (portRoute != null) {
            return portRoute;
        }
        return isAutoRoutedWithoutWaypoints(edge) ? null : edgeRoute(edge, source, target);
    }

    private List<CanvasPointData> portBasedRoute(CanvasCellData edge, CanvasCellData source, CanvasCellData target) {
        String style = StringUtils.defaultString(edge.getStyle());
        Double exitX = styleFraction(style, "exitX");
        Double exitY = styleFraction(style, "exitY");
        Double entryX = styleFraction(style, "entryX");
        Double entryY = styleFraction(style, "entryY");
        if (exitX == null || exitY == null || entryX == null || entryY == null) {
            return null;
        }
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
                route.add(CanvasPointData.builder().x(midX).y(exitPoint.getY()).build());
                route.add(CanvasPointData.builder().x(midX).y(entryPoint.getY()).build());
            } else if (!exitHorizontal && !entryHorizontal) {
                double midY = (exitPoint.getY() + entryPoint.getY()) / 2D;
                route.add(CanvasPointData.builder().x(exitPoint.getX()).y(midY).build());
                route.add(CanvasPointData.builder().x(entryPoint.getX()).y(midY).build());
            } else if (exitHorizontal) {
                route.add(CanvasPointData.builder().x(entryPoint.getX()).y(exitPoint.getY()).build());
            } else {
                route.add(CanvasPointData.builder().x(exitPoint.getX()).y(entryPoint.getY()).build());
            }
        }
        route.add(entryPoint);
        return route;
    }

    private Double styleFraction(String style, String token) {
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

    private boolean isAutoRoutedWithoutWaypoints(CanvasCellData edge) {
        if (edge.getPoints() != null && !edge.getPoints().isEmpty()) {
            return false;
        }
        String style = StringUtils.defaultString(edge.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("orthogonaledgestyle")
                || style.contains("elbowedgestyle")
                || style.contains("entityrelationedgestyle")
                || style.contains("isometricedgestyle");
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
        route.add(edgeEndpoint(edge, source, true, firstDirection));
        route.addAll(waypoints);
        route.add(edgeEndpoint(edge, target, false, lastDirection));
        return route;
    }

    private CanvasPointData edgeEndpoint(CanvasCellData edge,
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
            return CanvasPointData.builder()
                    .x(node.getX() + xFraction * node.getWidth())
                    .y(node.getY() + yFraction * node.getHeight())
                    .build();
        }
        return anchorToward(node, null, fallbackDirection);
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

    private List<CanvasQualityIssue> toQualityIssues(List<CanvasAnalysisIssue> issues,
                                                     DiagramQualityProfile profile) {
        return issues.stream().map(issue -> toQualityIssue(issue, profile)).toList();
    }

    private CanvasQualityIssue toQualityIssue(CanvasAnalysisIssue issue,
                                              DiagramQualityProfile profile) {
        Set<String> targets = new LinkedHashSet<>(issue.getTargetCellIds() == null
                ? List.of()
                : issue.getTargetCellIds());
        String fingerprint = issue.getType() + "|" + String.join(",", targets) + "|"
                + StringUtils.defaultString(issue.getMessage());
        CanvasRepairability repairability = typedRepairability(issue.getRepairability());
        if ((repairability == CanvasRepairability.SAFE_AUTOMATIC
                || repairability == CanvasRepairability.CONDITIONAL_AUTOMATIC)
                && !profile.allowsAutomaticRepair(issue.getType())) {
            repairability = CanvasRepairability.MODEL_ASSISTED;
        }
        return new CanvasQualityIssue(
                "det-" + UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)),
                issue.getType(),
                CanvasIssueCategory.fromLegacy(issue.getCategory()),
                CanvasIssueSeverity.fromLegacy(issue.getSeverity()),
                repairability,
                1D,
                targets,
                new CanvasIssueEvidence(CanvasEvidenceSource.DETERMINISTIC, issue.getType().name(),
                        Map.of("legacyRepairability", StringUtils.defaultString(issue.getRepairability()))),
                issue.getMessage(),
                profile.version());
    }

    private CanvasRepairability typedRepairability(String repairability) {
        return switch (StringUtils.defaultString(repairability)) {
            case "auto_repair" -> CanvasRepairability.SAFE_AUTOMATIC;
            case "auto_reroute" -> CanvasRepairability.CONDITIONAL_AUTOMATIC;
            case "candidate" -> CanvasRepairability.MODEL_ASSISTED;
            default -> CanvasRepairability.MANUAL_ONLY;
        };
    }

    private LayoutFamily resolveLayoutFamily(DiagramQualityProfile profile,
                                             LayoutFamily requestedLayout,
                                             String inferredLayoutMode) {
        if (requestedLayout != null && profile.allowedLayoutFamilies().contains(requestedLayout)) {
            return requestedLayout;
        }
        LayoutFamily inferredLayout = switch (StringUtils.defaultString(inferredLayoutMode)) {
            case "radial" -> LayoutFamily.RADIAL;
            case "illustration" -> LayoutFamily.FREEFORM;
            default -> profile.defaultLayoutFamily();
        };
        return profile.allowedLayoutFamilies().contains(inferredLayout)
                ? inferredLayout
                : profile.defaultLayoutFamily();
    }

    private String resolveSeverity(List<CanvasAnalysisIssue> issues) {
        int rank = 0;
        for (CanvasAnalysisIssue issue : issues) {
            rank = Math.max(rank, severityRank(issue.getSeverity()));
        }
        return switch (rank) {
            case 3 -> "critical";
            case 2 -> "major";
            case 1 -> "minor";
            default -> "ok";
        };
    }

    // Only major/critical findings invalidate the canvas; minor aesthetic notes (e.g. removable
    // waypoints) surface as issues without failing the reviewer's approve check.
    private boolean hasBlockingIssue(List<CanvasAnalysisIssue> issues) {
        return issues.stream().anyMatch(issue -> severityRank(issue.getSeverity()) >= 2);
    }

    private int severityRank(String severity) {
        return switch (StringUtils.defaultString(severity)) {
            case "critical" -> 3;
            case "major" -> 2;
            case "minor" -> 1;
            default -> 1;
        };
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

    private boolean isTextCell(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.startsWith("text;") || style.contains("shape=text");
    }

    private boolean isUmlLifeline(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("umllifeline");
    }

    private boolean isRoundedNode(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        return style.contains("rounded=1") || style.contains("arcsize=");
    }

    private boolean hasOpaqueTextBackground(CanvasCellData cell) {
        String style = StringUtils.defaultString(cell.getStyle()).toLowerCase(Locale.ROOT);
        // Only a real fill / label background masks content; a white stroke or border does not.
        return style.contains("fillcolor=#ffffff")
                || style.contains("fillcolor=white")
                || style.contains("labelbackgroundcolor=#ffffff")
                || style.contains("labelbackgroundcolor=white");
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

    private record PortSet(Double exitX, Double exitY, Double entryX, Double entryY) {
        private boolean complete() {
            return exitX != null && exitY != null && entryX != null && entryY != null;
        }
    }

    private record EndpointPortBinding(String edgeId, String nodeId, PortSide side, double track) {
    }

    private enum PortSide {
        LEFT("left"),
        RIGHT("right"),
        TOP("top"),
        BOTTOM("bottom");

        private final String label;

        PortSide(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }

        private Double track(Double xFraction, Double yFraction) {
            return this == LEFT || this == RIGHT ? yFraction : xFraction;
        }
    }

    private record Bounds(double x, double y, double width, double height) {
    }

}
