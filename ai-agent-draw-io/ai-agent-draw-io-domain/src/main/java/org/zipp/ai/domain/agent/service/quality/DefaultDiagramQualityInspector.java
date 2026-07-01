package org.zipp.ai.domain.agent.service.quality;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasEdge;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasNode;
import org.zipp.ai.domain.agent.model.valobj.canvas.DrawioCanvasSnapshot;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.quality.DiagramQualityReport;
import org.zipp.ai.domain.agent.model.valobj.quality.QualityIssue;
import org.zipp.ai.domain.agent.service.IDiagramQualityInspector;
import org.zipp.ai.domain.agent.service.IDrawioCanvasSnapshotService;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DefaultDiagramQualityInspector implements IDiagramQualityInspector {

    private static final double MIN_GAP = 28D;
    private static final double ALIGNMENT_TOLERANCE = 10D;
    private static final double CONTAINER_PADDING = 8D;

    @Resource
    private IDrawioCanvasSnapshotService canvasSnapshotService;

    @Resource
    private ICanvasAnalyzer canvasAnalyzer;

    public DefaultDiagramQualityInspector() {
    }

    public DefaultDiagramQualityInspector(IDrawioCanvasSnapshotService canvasSnapshotService) {
        this.canvasSnapshotService = canvasSnapshotService;
    }

    @Override
    public DiagramQualityReport inspect(String message, String diagramType) {
        DrawioCanvasSnapshot snapshot = snapshotService().fromMessage(message, diagramType);
        return inspect(snapshot);
    }

    public DiagramQualityReport inspect(DrawioCanvasSnapshot snapshot) {
        List<QualityIssue> layoutIssues = new ArrayList<>();
        List<QualityIssue> readabilityIssues = new ArrayList<>();
        List<QualityIssue> edgeIssues = new ArrayList<>();
        List<QualityIssue> semanticHints = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        if (null == snapshot || !snapshot.isValid()) {
            semanticHints.add(issue("semantic", "empty_or_invalid_canvas", "medium", invalidCanvasMessage(snapshot), null));
            recommendations.add("Provide or create a valid Draw.io diagram before asking for detailed visual and content review.");
            return buildReport(snapshot, layoutIssues, readabilityIssues, edgeIssues, semanticHints, recommendations);
        }

        List<CanvasNode> nodes = snapshot.getNodes();
        List<CanvasEdge> edges = snapshot.getEdges();
        addAnalyzerIssues(snapshot, layoutIssues, edgeIssues, semanticHints, recommendations);
        inspectLayout(nodes, layoutIssues, recommendations);
        inspectReadability(nodes, readabilityIssues, recommendations);
        inspectEdges(nodes, edges, edgeIssues, recommendations);
        inspectSemantics(nodes, edges, snapshot.getDiagramType(), semanticHints, recommendations);
        dedupeRecommendations(recommendations);

        return buildReport(snapshot, layoutIssues, readabilityIssues, edgeIssues, semanticHints, recommendations);
    }

    private IDrawioCanvasSnapshotService snapshotService() {
        if (null == canvasSnapshotService) {
            canvasSnapshotService = new DefaultDrawioCanvasSnapshotService();
        }
        return canvasSnapshotService;
    }

    private ICanvasAnalyzer analyzer() {
        if (null == canvasAnalyzer) {
            canvasAnalyzer = new DefaultCanvasAnalyzer();
        }
        return canvasAnalyzer;
    }

    private void addAnalyzerIssues(DrawioCanvasSnapshot snapshot,
                                   List<QualityIssue> layoutIssues,
                                   List<QualityIssue> edgeIssues,
                                   List<QualityIssue> semanticHints,
                                   List<String> recommendations) {
        // Structural and geometry findings come from the shared analyzer; local checks keep readability heuristics.
        CanvasAnalysis analysis = analyzer().analyze(snapshot.getRawXml(), snapshot.getDiagramType());
        boolean mapped = false;
        for (CanvasAnalysisIssue analyzerIssue : analysis.getIssues()) {
            CanvasIssueType type = analyzerIssue.getType();
            if (CanvasIssueType.NODE_OVERLAP == type) {
                layoutIssues.add(issue("layout", "node_overlap", "high", analyzerIssue.getMessage(), firstTarget(analyzerIssue)));
                mapped = true;
            } else if (CanvasIssueType.MISSING_GEOMETRY == type) {
                layoutIssues.add(issue("layout", "missing_geometry", "high", analyzerIssue.getMessage(), firstTarget(analyzerIssue)));
                mapped = true;
            } else if (CanvasIssueType.BROKEN_EDGE == type) {
                edgeIssues.add(issue("edges", "broken_edge", "high", analyzerIssue.getMessage(), firstTarget(analyzerIssue)));
                mapped = true;
            } else if (CanvasIssueType.EDGE_NODE_CROSSING == type) {
                edgeIssues.add(issue("edges", "edge_node_crossing", "medium", analyzerIssue.getMessage(), firstTarget(analyzerIssue)));
                mapped = true;
            } else if (CanvasIssueType.DUP_ID == type) {
                semanticHints.add(issue("structure", "dup_id", "high", analyzerIssue.getMessage(), firstTarget(analyzerIssue)));
                mapped = true;
            } else if (CanvasIssueType.INVALID_XML == type) {
                semanticHints.add(issue("structure", "invalid_xml", "high", analyzerIssue.getMessage(), firstTarget(analyzerIssue)));
                mapped = true;
            }
        }
        if (mapped) {
            recommendations.add("Repair structural and geometry issues using the deterministic canvas analysis targets before relying on visual review.");
        }
    }

    private String firstTarget(CanvasAnalysisIssue issue) {
        if (issue.getTargetCellIds() == null || issue.getTargetCellIds().isEmpty()) {
            return null;
        }
        return issue.getTargetCellIds().get(0);
    }

    private void inspectLayout(List<CanvasNode> nodes, List<QualityIssue> issues, List<String> recommendations) {
        for (int i = 0; i < nodes.size(); i++) {
            CanvasNode a = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                CanvasNode b = nodes.get(j);
                if (shouldSkipPairForOverlap(a, b)) {
                    continue;
                }
                if (intersects(a, b)) {
                    continue;
                } else if (isTooClose(a, b)) {
                    issues.add(issue("layout", "tight_spacing", "medium", "Two nearby nodes have very tight spacing: " + labelPair(a, b), a.getId()));
                }
            }
        }

        inspectContainerOverflow(nodes, issues);
        inspectAlignment(nodes, issues);
        inspectDensity(nodes, issues);

        if (!issues.isEmpty()) {
            recommendations.add("Improve layout by increasing spacing, aligning peer elements, and keeping child elements inside their containers.");
        }
    }

    private void inspectContainerOverflow(List<CanvasNode> nodes, List<QualityIssue> issues) {
        Map<String, CanvasNode> nodeMap = toNodeMap(nodes);
        for (CanvasNode node : nodes) {
            CanvasNode parent = nodeMap.get(node.getParentId());
            if (null == parent || !parent.isContainer()) {
                continue;
            }
            boolean outside = node.getX() < parent.getX() + CONTAINER_PADDING
                    || node.getY() < parent.getY() + CONTAINER_PADDING
                    || node.maxX() > parent.maxX() - CONTAINER_PADDING
                    || node.maxY() > parent.maxY() - CONTAINER_PADDING;
            if (outside) {
                issues.add(issue("layout", "child_outside_container", "medium", "A child node may be outside or too close to its container boundary: " + node.getLabel(), node.getId()));
            }
        }
    }

    private void inspectAlignment(List<CanvasNode> nodes, List<QualityIssue> issues) {
        List<CanvasNode> visualNodes = visualNodes(nodes);
        if (visualNodes.size() < 4) {
            return;
        }

        int aligned = 0;
        for (CanvasNode node : visualNodes) {
            if (hasAlignedPeer(node, visualNodes)) {
                aligned++;
            }
        }
        if (aligned < visualNodes.size() / 2) {
            issues.add(issue("layout", "weak_alignment", "low", "Most peer nodes are not aligned on stable rows or columns.", null));
        }
    }

    private void inspectDensity(List<CanvasNode> nodes, List<QualityIssue> issues) {
        List<CanvasNode> visualNodes = visualNodes(nodes);
        if (visualNodes.size() < 8) {
            return;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double area = 0D;
        for (CanvasNode node : visualNodes) {
            minX = Math.min(minX, node.getX());
            minY = Math.min(minY, node.getY());
            maxX = Math.max(maxX, node.maxX());
            maxY = Math.max(maxY, node.maxY());
            area += node.getWidth() * node.getHeight();
        }
        double boundsArea = Math.max(1D, (maxX - minX) * (maxY - minY));
        if (area / boundsArea > 0.58D) {
            issues.add(issue("layout", "crowded_canvas", "medium", "The diagram appears visually dense; elements may need more whitespace.", null));
        }
    }

    private void inspectReadability(List<CanvasNode> nodes, List<QualityIssue> issues, List<String> recommendations) {
        Map<String, Integer> labelCounts = new HashMap<>();
        for (CanvasNode node : nodes) {
            String normalizedLabel = normalizeLabel(node.getLabel());
            if (StringUtils.isNotBlank(normalizedLabel)) {
                labelCounts.merge(normalizedLabel, 1, Integer::sum);
            }

            if (StringUtils.isBlank(normalizedLabel) && !node.isContainer() && !node.isText()) {
                issues.add(issue("readability", "unlabeled_node", "low", "A visible node has no readable label.", node.getId()));
            }
            if (estimatedTextWidth(normalizedLabel) > node.getWidth() * 1.75D) {
                issues.add(issue("readability", "long_label_risk", "medium", "A node label may be too long for its box: " + node.getLabel(), node.getId()));
            }
            if (!node.isText() && node.getWidth() > 0D && node.getWidth() < 70D) {
                issues.add(issue("readability", "small_node_width", "low", "A node may be too narrow for readable text: " + node.getLabel(), node.getId()));
            }
            if (!node.isText() && node.getHeight() > 0D && node.getHeight() < 28D) {
                issues.add(issue("readability", "small_node_height", "low", "A node may be too short for readable text: " + node.getLabel(), node.getId()));
            }
            if (hasOpaqueStandaloneTextStyle(node)) {
                issues.add(issue("readability", "opaque_text_background", "medium", "Standalone text should use transparent fill and no visible border: " + node.getLabel(), node.getId()));
            }
        }

        for (Map.Entry<String, Integer> entry : labelCounts.entrySet()) {
            if (entry.getValue() > 1) {
                issues.add(issue("readability", "duplicate_label", "medium", "The label appears multiple times and may represent duplicated content: " + entry.getKey(), null));
            }
        }

        if (!issues.isEmpty()) {
            recommendations.add("Improve readability by shortening long labels, widening dense nodes, and removing duplicate or unclear labels.");
        }
    }

    private void inspectEdges(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues, List<String> recommendations) {
        Map<String, CanvasNode> nodeMap = toNodeMap(nodes);
        Set<String> connectedNodeIds = new HashSet<>();

        for (CanvasEdge edge : edges) {
            CanvasNode source = nodeMap.get(edge.getSource());
            CanvasNode target = nodeMap.get(edge.getTarget());
            if (StringUtils.equals(edge.getSource(), edge.getTarget()) && StringUtils.isNotBlank(edge.getSource())) {
                issues.add(issue("edges", "self_loop", "low", "An edge loops back to the same node; verify whether this is intentional.", edge.getId()));
            }
            if (null == source || null == target) {
                continue;
            }

            connectedNodeIds.add(source.getId());
            connectedNodeIds.add(target.getId());
            if (estimatedTextWidth(edge.getLabel()) > 180D) {
                issues.add(issue("edges", "long_edge_label", "low", "An edge label may be too long and hard to scan: " + edge.getLabel(), edge.getId()));
            }
        }

        inspectEdgeCrossings(edges, nodeMap, issues);
        inspectIsolatedNodes(nodes, connectedNodeIds, issues);

        if (!issues.isEmpty()) {
            recommendations.add("Improve edge quality by connecting intentional nodes, rerouting lines around node bodies, and reducing edge crossings.");
        }
    }

    private void inspectEdgeCrossings(List<CanvasEdge> edges, Map<String, CanvasNode> nodeMap, List<QualityIssue> issues) {
        int crossings = 0;
        for (int i = 0; i < edges.size(); i++) {
            CanvasEdge a = edges.get(i);
            CanvasNode aSource = nodeMap.get(a.getSource());
            CanvasNode aTarget = nodeMap.get(a.getTarget());
            if (null == aSource || null == aTarget) {
                continue;
            }
            for (int j = i + 1; j < edges.size(); j++) {
                CanvasEdge b = edges.get(j);
                if (shareEndpoint(a, b)) {
                    continue;
                }
                CanvasNode bSource = nodeMap.get(b.getSource());
                CanvasNode bTarget = nodeMap.get(b.getTarget());
                if (null == bSource || null == bTarget) {
                    continue;
                }
                if (lineIntersectsLine(aSource.centerX(), aSource.centerY(), aTarget.centerX(), aTarget.centerY(),
                        bSource.centerX(), bSource.centerY(), bTarget.centerX(), bTarget.centerY())) {
                    crossings++;
                }
            }
        }
        if (crossings >= 2) {
            issues.add(issue("edges", "edge_crossing_risk", "medium", "Several edges appear to cross each other; routing may be hard to follow.", null));
        }
    }

    private void inspectIsolatedNodes(List<CanvasNode> nodes, Set<String> connectedNodeIds, List<QualityIssue> issues) {
        if (visualNodes(nodes).size() <= 1) {
            return;
        }
        for (CanvasNode node : nodes) {
            if (node.isText() || node.isContainer()) {
                continue;
            }
            if (!connectedNodeIds.contains(node.getId())) {
                issues.add(issue("edges", "isolated_node", "low", "A node has no connected edges: " + node.getLabel(), node.getId()));
            }
        }
    }

    private void inspectSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, String diagramType, List<QualityIssue> issues, List<String> recommendations) {
        String normalizedType = null == diagramType ? "" : diagramType.toLowerCase(Locale.ROOT);
        List<CanvasNode> visualNodes = visualNodes(nodes);
        if (visualNodes.isEmpty()) {
            return;
        }

        if (edges.isEmpty() && visualNodes.size() > 1) {
            issues.add(issue("semantic", "missing_relationships", "medium", "The diagram has multiple nodes but no relationships.", null));
        }

        switch (normalizedType) {
            case "uml_class":
                inspectUmlSemantics(visualNodes, edges, issues);
                break;
            case "architecture":
                inspectArchitectureSemantics(nodes, edges, issues);
                break;
            case "flowchart":
                inspectFlowchartSemantics(visualNodes, edges, issues);
                break;
            case "sequence":
                inspectSequenceSemantics(visualNodes, edges, issues);
                break;
            case "er":
                inspectErSemantics(visualNodes, edges, issues);
                break;
            case "usecase":
                inspectUseCaseSemantics(visualNodes, edges, issues);
                break;
            case "state":
                inspectStateSemantics(visualNodes, edges, issues);
                break;
            default:
                break;
        }

        if (!issues.isEmpty()) {
            recommendations.add("Review whether the diagram has enough meaningful relationships and follows the conventions of its diagram type.");
        }
    }

    private void inspectUmlSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        if (nodes.size() >= 3 && edges.size() < Math.max(1, nodes.size() / 2)) {
            issues.add(issue("semantic", "weak_model_relationships", "medium", "The UML/class model may need more class relationships.", null));
        }
        long classLikeNodes = nodes.stream().filter(node -> containsAny(node.getLabel(), ":", "()", "+", "-")).count();
        if (nodes.size() >= 3 && classLikeNodes < nodes.size() / 2) {
            issues.add(issue("semantic", "weak_class_detail", "low", "Several UML class nodes do not show clear attributes or methods.", null));
        }
    }

    private void inspectArchitectureSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        long containers = nodes.stream().filter(CanvasNode::isContainer).count();
        if (nodes.size() >= 5 && containers == 0) {
            issues.add(issue("semantic", "missing_architecture_boundary", "medium", "The architecture diagram may need boundaries, layers, or grouped subsystems.", null));
        }
        if (nodes.size() >= 5 && edges.size() < 3) {
            issues.add(issue("semantic", "weak_architecture_dependencies", "medium", "The architecture diagram may need clearer dependency or data-flow relationships.", null));
        }
    }

    private void inspectFlowchartSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        boolean hasStartOrEnd = nodes.stream().anyMatch(node -> containsAnyIgnoreCase(node.getLabel(), "start", "end", "开始", "结束"));
        if (!hasStartOrEnd && nodes.size() >= 3) {
            issues.add(issue("semantic", "missing_flow_boundary", "low", "The flowchart may need a clear start or end node.", null));
        }
        if (nodes.size() >= 4 && edges.size() < nodes.size() - 1) {
            issues.add(issue("semantic", "weak_flow_connectivity", "medium", "The flowchart may not connect all process steps clearly.", null));
        }
    }

    private void inspectSequenceSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        if (nodes.size() >= 3 && edges.size() < 2) {
            issues.add(issue("semantic", "weak_sequence_messages", "medium", "The sequence diagram may need clearer ordered messages between participants.", null));
        }
    }

    private void inspectErSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        boolean hasKeyNotation = nodes.stream().anyMatch(node -> containsAnyIgnoreCase(node.getLabel(), "pk", "fk", "id", "key", "主键", "外键"));
        if (!hasKeyNotation && nodes.size() >= 2) {
            issues.add(issue("semantic", "missing_key_notation", "medium", "The ER diagram may need primary key or foreign key notation.", null));
        }
        if (nodes.size() >= 3 && edges.size() < 2) {
            issues.add(issue("semantic", "weak_entity_relationships", "medium", "The ER diagram may need more explicit entity relationships.", null));
        }
    }

    private void inspectUseCaseSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        boolean hasActor = nodes.stream().anyMatch(node -> containsAnyIgnoreCase(node.getStyle(), "actor") || containsAnyIgnoreCase(node.getLabel(), "actor", "user", "admin", "用户"));
        boolean hasBoundary = nodes.stream().anyMatch(CanvasNode::isContainer);
        if (!hasActor) {
            issues.add(issue("semantic", "missing_actor", "medium", "The use case diagram should include at least one actor.", null));
        }
        if (!hasBoundary && nodes.size() >= 3) {
            issues.add(issue("semantic", "missing_system_boundary", "low", "The use case diagram may need a system boundary.", null));
        }
        if (edges.isEmpty() && nodes.size() > 1) {
            issues.add(issue("semantic", "missing_actor_usecase_links", "medium", "Actors and use cases should be connected.", null));
        }
    }

    private void inspectStateSemantics(List<CanvasNode> nodes, List<CanvasEdge> edges, List<QualityIssue> issues) {
        boolean hasInitialOrFinal = nodes.stream().anyMatch(node -> containsAnyIgnoreCase(node.getLabel(), "start", "initial", "final", "end", "开始", "结束"));
        if (!hasInitialOrFinal && nodes.size() >= 3) {
            issues.add(issue("semantic", "missing_initial_or_final_state", "low", "The state diagram may need an initial or final state.", null));
        }
        if (nodes.size() >= 3 && edges.size() < nodes.size() - 1) {
            issues.add(issue("semantic", "weak_state_transitions", "medium", "The state diagram may need more explicit transitions.", null));
        }
    }

    private DiagramQualityReport buildReport(DrawioCanvasSnapshot snapshot,
                                             List<QualityIssue> layoutIssues,
                                             List<QualityIssue> readabilityIssues,
                                             List<QualityIssue> edgeIssues,
                                             List<QualityIssue> semanticHints,
                                             List<String> recommendations) {
        String overallRisk = resolveRisk(layoutIssues, readabilityIssues, edgeIssues, semanticHints);
        return DiagramQualityReport.builder()
                .nodeCount(null == snapshot ? 0 : snapshot.nodeCount())
                .edgeCount(null == snapshot ? 0 : snapshot.edgeCount())
                .diagramType(null == snapshot ? "unknown" : StringUtils.defaultIfBlank(snapshot.getDiagramType(), "unknown"))
                .canvasSummary(null == snapshot ? "" : snapshot.getSummary())
                .overallRisk(overallRisk)
                .layoutIssues(layoutIssues)
                .readabilityIssues(readabilityIssues)
                .edgeIssues(edgeIssues)
                .semanticHints(semanticHints)
                .recommendations(recommendations)
                .build();
    }

    private String resolveRisk(List<QualityIssue>... issueGroups) {
        int high = 0;
        int medium = 0;
        for (List<QualityIssue> issues : issueGroups) {
            for (QualityIssue issue : issues) {
                if ("high".equals(issue.getSeverity())) {
                    high++;
                } else if ("medium".equals(issue.getSeverity())) {
                    medium++;
                }
            }
        }
        if (high > 0) return "high";
        if (medium > 2) return "medium";
        if (medium > 0) return "low";
        return "ok";
    }

    private QualityIssue issue(String category, String type, String severity, String message, String cellId) {
        return QualityIssue.builder()
                .category(category)
                .type(type)
                .severity(severity)
                .message(message)
                .cellId(cellId)
                .build();
    }

    private Map<String, CanvasNode> toNodeMap(List<CanvasNode> nodes) {
        Map<String, CanvasNode> nodeMap = new HashMap<>();
        for (CanvasNode node : nodes) {
            nodeMap.put(node.getId(), node);
        }
        return nodeMap;
    }

    private List<CanvasNode> visualNodes(List<CanvasNode> nodes) {
        List<CanvasNode> result = new ArrayList<>();
        for (CanvasNode node : nodes) {
            if (!node.isText() && !node.isContainer()) {
                result.add(node);
            }
        }
        return result;
    }

    private boolean shouldSkipPairForOverlap(CanvasNode a, CanvasNode b) {
        return StringUtils.equals(a.getParentId(), b.getId())
                || StringUtils.equals(b.getParentId(), a.getId())
                || a.isContainer()
                || b.isContainer();
    }

    private boolean intersects(CanvasNode a, CanvasNode b) {
        return a.getX() < b.maxX() && a.maxX() > b.getX() && a.getY() < b.maxY() && a.maxY() > b.getY();
    }

    private boolean isTooClose(CanvasNode a, CanvasNode b) {
        double horizontalGap = Math.max(0D, Math.max(b.getX() - a.maxX(), a.getX() - b.maxX()));
        double verticalGap = Math.max(0D, Math.max(b.getY() - a.maxY(), a.getY() - b.maxY()));
        boolean rowOverlap = a.getY() < b.maxY() && a.maxY() > b.getY();
        boolean colOverlap = a.getX() < b.maxX() && a.maxX() > b.getX();
        return (rowOverlap && horizontalGap < MIN_GAP) || (colOverlap && verticalGap < MIN_GAP);
    }

    private boolean hasAlignedPeer(CanvasNode node, List<CanvasNode> nodes) {
        for (CanvasNode peer : nodes) {
            if (node == peer) {
                continue;
            }
            if (Math.abs(node.getX() - peer.getX()) <= ALIGNMENT_TOLERANCE
                    || Math.abs(node.getY() - peer.getY()) <= ALIGNMENT_TOLERANCE
                    || Math.abs(node.centerX() - peer.centerX()) <= ALIGNMENT_TOLERANCE
                    || Math.abs(node.centerY() - peer.centerY()) <= ALIGNMENT_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private boolean lineIntersectsLine(double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4) {
        double denominator = ((y4 - y3) * (x2 - x1)) - ((x4 - x3) * (y2 - y1));
        if (0D == denominator) {
            return false;
        }
        double ua = (((x4 - x3) * (y1 - y3)) - ((y4 - y3) * (x1 - x3))) / denominator;
        double ub = (((x2 - x1) * (y1 - y3)) - ((y2 - y1) * (x1 - x3))) / denominator;
        return ua >= 0D && ua <= 1D && ub >= 0D && ub <= 1D;
    }

    private boolean shareEndpoint(CanvasEdge a, CanvasEdge b) {
        return StringUtils.equals(a.getSource(), b.getSource())
                || StringUtils.equals(a.getSource(), b.getTarget())
                || StringUtils.equals(a.getTarget(), b.getSource())
                || StringUtils.equals(a.getTarget(), b.getTarget());
    }

    private String normalizeLabel(String label) {
        return StringUtils.defaultString(label).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private double estimatedTextWidth(String text) {
        if (StringUtils.isBlank(text)) {
            return 0D;
        }
        double width = 0D;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += c > 127 ? 12D : 7D;
        }
        return width;
    }

    private boolean hasOpaqueStandaloneTextStyle(CanvasNode node) {
        if (!node.isText()) {
            return false;
        }
        String style = node.getStyle();
        return hasVisibleStyleValue(style, "fillColor")
                || hasVisibleStyleValue(style, "strokeColor")
                || hasVisibleStyleValue(style, "labelBackgroundColor")
                || hasVisibleStyleValue(style, "labelBorderColor");
    }

    private boolean hasVisibleStyleValue(String style, String key) {
        String value = styleValue(style, key);
        return null != value && !value.isEmpty() && !"none".equalsIgnoreCase(value);
    }

    private String styleValue(String style, String key) {
        if (StringUtils.isBlank(style)) {
            return null;
        }
        String[] tokens = style.split(";");
        for (String token : tokens) {
            int separatorIndex = token.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String tokenKey = token.substring(0, separatorIndex).trim();
            if (key.equalsIgnoreCase(tokenKey)) {
                return token.substring(separatorIndex + 1).trim();
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyIgnoreCase(String text, String... keywords) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String labelPair(CanvasNode a, CanvasNode b) {
        return StringUtils.defaultIfBlank(a.getLabel(), a.getId()) + " / " + StringUtils.defaultIfBlank(b.getLabel(), b.getId());
    }

    private String invalidCanvasMessage(DrawioCanvasSnapshot snapshot) {
        if (null == snapshot || StringUtils.isBlank(snapshot.getErrorMessage())) {
            return "No drawable Draw.io XML was found in the current context.";
        }
        return snapshot.getErrorMessage();
    }

    private void dedupeRecommendations(List<String> recommendations) {
        Set<String> seen = new HashSet<>();
        recommendations.removeIf(item -> !seen.add(item));
    }

}
