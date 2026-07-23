package org.zipp.ai.domain.multimodal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic projection from explicit observed topology to editable mxGraph cells. */
public final class DefaultImageToDiagramModule implements ImageToDiagramModule {
    private static final int CANVAS_WIDTH = 1_200;
    private static final int CANVAS_HEIGHT = 800;
    private static final double CRITICAL_EDGE_CONFIDENCE = 0.75;

    @Override
    public ImageToDiagramOutcome convert(ImageToDiagramCommand command) {
        Objects.requireNonNull(command, "command");
        ObservedDiagramGraph graph = command.graph();
        List<String> invalid = validateReferences(graph);
        if (!invalid.isEmpty()) return new ImageToDiagramOutcome.Rejected(invalid);
        Map<String, DirectClarification> clarifications = command.clarifications().stream()
                .collect(java.util.stream.Collectors.toMap(
                        DirectClarification::reasonCode,
                        value -> value,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        ObservedDiagramGraph effectiveGraph = applyDirectionClarifications(graph, clarifications);

        List<String> confirmation = graph.unresolvedItems().stream()
                .map(item -> "UNRESOLVED:" + item.reason())
                .filter(reason -> !accepted(clarifications, reason, observedValue(graph, reason)))
                .collect(
                        java.util.stream.Collectors.toCollection(ArrayList::new));
        graph.nodes().stream()
                .filter(node -> node.confidence() < CRITICAL_EDGE_CONFIDENCE)
                .map(node -> "LOW_CONFIDENCE_NODE_TEXT:" + node.id())
                .filter(reason -> !accepted(clarifications, reason, observedValue(graph, reason)))
                .forEach(confirmation::add);
        graph.groups().stream()
                .filter(group -> group.confidence() < CRITICAL_EDGE_CONFIDENCE)
                .map(group -> "LOW_CONFIDENCE_GROUP_TEXT:" + group.id())
                .filter(reason -> !accepted(clarifications, reason, observedValue(graph, reason)))
                .forEach(confirmation::add);
        graph.edges().stream()
                .filter(edge -> edge.confidence() < CRITICAL_EDGE_CONFIDENCE)
                .map(edge -> "LOW_CONFIDENCE_EDGE:" + edge.id())
                .filter(reason -> !accepted(clarifications, reason, observedValue(graph, reason)))
                .forEach(confirmation::add);
        effectiveGraph.edges().stream()
                .filter(edge -> edge.direction() == ObservedDiagramGraph.EdgeDirection.NONE)
                .map(edge -> "UNRESOLVED_EDGE_DIRECTION:" + edge.id())
                .filter(reason -> !undirected(clarifications, reason, observedValue(graph, reason)))
                .forEach(confirmation::add);
        if (!confirmation.isEmpty()) {
            return new ImageToDiagramOutcome.NeedsConfirmation(
                    confirmation, observedValues(graph, confirmation));
        }

        Map<String, ObservedDiagramGraph.Group> groups = indexGroups(effectiveGraph.groups());
        List<String> cellIds = new ArrayList<>();
        StringBuilder xml = new StringBuilder(1_024);
        xml.append("<mxGraphModel><root><mxCell id=\"0\"/>")
                .append("<mxCell id=\"1\" parent=\"0\"/>");
        for (ObservedDiagramGraph.Group group : effectiveGraph.groups()) {
            appendGroup(xml, group);
            cellIds.add(groupCellId(group.id()));
        }
        for (ObservedDiagramGraph.Node node : effectiveGraph.nodes()) {
            appendNode(xml, node, groups.get(node.groupId()));
            cellIds.add(nodeCellId(node.id()));
        }
        for (ObservedDiagramGraph.Edge edge : effectiveGraph.edges()) {
            appendEdge(xml, edge);
            cellIds.add(edgeCellId(edge.id()));
        }
        xml.append("</root></mxGraphModel>");
        return new ImageToDiagramOutcome.Converted(xml.toString(), cellIds, effectiveGraph);
    }

    private boolean accepted(Map<String, DirectClarification> clarifications,
                             String reason, String observedValue) {
        DirectClarification clarification = clarifications.get(reason);
        return matchesObservation(clarification, observedValue)
                && clarification.resolution() == DirectClarification.Resolution.ACCEPT_OBSERVED;
    }

    private boolean undirected(Map<String, DirectClarification> clarifications,
                               String reason, String observedValue) {
        DirectClarification clarification = clarifications.get(reason);
        return matchesObservation(clarification, observedValue)
                && clarification.resolution() == DirectClarification.Resolution.UNDIRECTED;
    }

    private boolean matchesObservation(DirectClarification clarification, String observedValue) {
        // A changed recognition result must be shown and confirmed again.
        return clarification != null && clarification.observedValue().equals(observedValue);
    }

    private ObservedDiagramGraph applyDirectionClarifications(
            ObservedDiagramGraph graph,
            Map<String, DirectClarification> clarifications) {
        List<ObservedDiagramGraph.Edge> edges = graph.edges().stream().map(edge -> {
            if (edge.direction() != ObservedDiagramGraph.EdgeDirection.NONE) return edge;
            DirectClarification clarification =
                    clarifications.get("UNRESOLVED_EDGE_DIRECTION:" + edge.id());
            DirectClarification.Resolution resolution = !matchesObservation(
                    clarification, edge.sourceId() + " → " + edge.targetId())
                    ? null : clarification.resolution();
            ObservedDiagramGraph.EdgeDirection direction =
                    resolution == null || resolution == DirectClarification.Resolution.ACCEPT_OBSERVED
                            ? edge.direction()
                            : switch (resolution) {
                                case FORWARD -> ObservedDiagramGraph.EdgeDirection.FORWARD;
                                case REVERSE -> ObservedDiagramGraph.EdgeDirection.REVERSE;
                                case BIDIRECTIONAL -> ObservedDiagramGraph.EdgeDirection.BIDIRECTIONAL;
                                case UNDIRECTED, ACCEPT_OBSERVED -> edge.direction();
                            };
            return new ObservedDiagramGraph.Edge(
                    edge.id(), edge.sourceId(), edge.targetId(), edge.label(), direction,
                    edge.lineStyle(), edge.waypoints(), edge.evidenceId(), edge.confidence());
        }).toList();
        return new ObservedDiagramGraph(
                graph.nodes(), edges, graph.groups(), graph.unresolvedItems());
    }

    private Map<String, String> observedValues(ObservedDiagramGraph graph,
                                               List<String> reasons) {
        Set<String> requested = Set.copyOf(reasons);
        Map<String, String> values = new LinkedHashMap<>();
        graph.nodes().forEach(node -> putObserved(values, requested,
                "LOW_CONFIDENCE_NODE_TEXT:" + node.id(), node.label()));
        graph.groups().forEach(group -> putObserved(values, requested,
                "LOW_CONFIDENCE_GROUP_TEXT:" + group.id(), group.label()));
        graph.edges().forEach(edge -> {
            putObserved(values, requested, "LOW_CONFIDENCE_EDGE:" + edge.id(),
                    edge.label().isBlank()
                            ? edge.sourceId() + " → " + edge.targetId()
                            : edge.label());
            putObserved(values, requested, "UNRESOLVED_EDGE_DIRECTION:" + edge.id(),
                    edge.sourceId() + " → " + edge.targetId());
        });
        graph.unresolvedItems().forEach(item -> putObserved(values, requested,
                "UNRESOLVED:" + item.reason(), item.suggestedConfirmation()));
        return Map.copyOf(values);
    }

    private String observedValue(ObservedDiagramGraph graph, String reason) {
        return observedValues(graph, List.of(reason)).getOrDefault(reason, "");
    }

    private void putObserved(Map<String, String> values, Set<String> requested,
                             String reason, String value) {
        if (requested.contains(reason) && value != null && !value.isBlank()) {
            values.put(reason, value);
        }
    }

    private List<String> validateReferences(ObservedDiagramGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph.nodes().isEmpty()) errors.add("NO_NODES");
        Set<String> nodeIds = new LinkedHashSet<>();
        for (ObservedDiagramGraph.Node node : graph.nodes()) {
            if (!nodeIds.add(node.id())) errors.add("DUPLICATE_NODE_ID:" + node.id());
        }
        Set<String> groupIds = new LinkedHashSet<>();
        for (ObservedDiagramGraph.Group group : graph.groups()) {
            if (!groupIds.add(group.id())) errors.add("DUPLICATE_GROUP_ID:" + group.id());
        }
        for (ObservedDiagramGraph.Node node : graph.nodes()) {
            if (!node.groupId().isBlank() && !groupIds.contains(node.groupId())) {
                errors.add("UNKNOWN_NODE_GROUP:" + node.id() + ":" + node.groupId());
            }
        }
        Set<String> edgeIds = new LinkedHashSet<>();
        for (ObservedDiagramGraph.Edge edge : graph.edges()) {
            if (!edgeIds.add(edge.id())) errors.add("DUPLICATE_EDGE_ID:" + edge.id());
            if (!nodeIds.contains(edge.sourceId())) {
                errors.add("UNKNOWN_EDGE_SOURCE:" + edge.id() + ":" + edge.sourceId());
            }
            if (!nodeIds.contains(edge.targetId())) {
                errors.add("UNKNOWN_EDGE_TARGET:" + edge.id() + ":" + edge.targetId());
            }
        }
        return List.copyOf(errors);
    }

    private Map<String, ObservedDiagramGraph.Group> indexGroups(List<ObservedDiagramGraph.Group> groups) {
        Map<String, ObservedDiagramGraph.Group> indexed = new LinkedHashMap<>();
        groups.forEach(group -> indexed.put(group.id(), group));
        return indexed;
    }

    private void appendGroup(StringBuilder xml, ObservedDiagramGraph.Group group) {
        xml.append("<mxCell id=\"").append(attribute(groupCellId(group.id())))
                .append("\" value=\"").append(attribute(group.label()))
                .append("\" style=\"").append(groupStyle(group.kind()))
                .append("\" vertex=\"1\" connectable=\"0\" parent=\"1\">");
        appendGeometry(xml, group.bounds(), null);
        xml.append("</mxCell>");
    }

    private void appendNode(StringBuilder xml, ObservedDiagramGraph.Node node,
                            ObservedDiagramGraph.Group parentGroup) {
        String parent = parentGroup == null ? "1" : groupCellId(parentGroup.id());
        xml.append("<mxCell id=\"").append(attribute(nodeCellId(node.id())))
                .append("\" value=\"").append(attribute(node.label()))
                .append("\" style=\"").append(shapeStyle(node.shape()))
                .append("\" parent=\"").append(attribute(parent))
                .append("\" vertex=\"1\">");
        appendGeometry(xml, node.bounds(), parentGroup == null ? null : parentGroup.bounds());
        xml.append("</mxCell>");
    }

    private void appendEdge(StringBuilder xml, ObservedDiagramGraph.Edge edge) {
        String source = edge.resolvedSourceId();
        String target = edge.resolvedTargetId();
        xml.append("<mxCell id=\"").append(attribute(edgeCellId(edge.id())))
                .append("\" edge=\"1\" parent=\"1\" source=\"").append(attribute(nodeCellId(source)))
                .append("\" target=\"").append(attribute(nodeCellId(target)))
                .append("\" value=\"").append(attribute(edge.label()))
                .append("\" style=\"").append(edgeStyle(edge.direction(), edge.lineStyle())).append("\">")
                .append("<mxGeometry relative=\"1\" as=\"geometry\">");
        if (!edge.waypoints().isEmpty()) {
            xml.append("<Array as=\"points\">");
            for (ObservedDiagramGraph.Point point : edge.waypoints()) {
                xml.append("<mxPoint x=\"").append(scale(point.x(), CANVAS_WIDTH))
                        .append("\" y=\"").append(scale(point.y(), CANVAS_HEIGHT)).append("\"/>");
            }
            xml.append("</Array>");
        }
        xml.append("</mxGeometry></mxCell>");
    }

    private void appendGeometry(StringBuilder xml, ObservationBounds bounds,
                                ObservationBounds parentBounds) {
        double originX = parentBounds == null ? 0 : parentBounds.x();
        double originY = parentBounds == null ? 0 : parentBounds.y();
        xml.append("<mxGeometry x=\"").append(scale(bounds.x() - originX, CANVAS_WIDTH))
                .append("\" y=\"").append(scale(bounds.y() - originY, CANVAS_HEIGHT))
                .append("\" width=\"").append(scale(bounds.width(), CANVAS_WIDTH))
                .append("\" height=\"").append(scale(bounds.height(), CANVAS_HEIGHT))
                .append("\" as=\"geometry\"/>");
    }

    private String shapeStyle(ObservedDiagramGraph.Shape shape) {
        return switch (shape) {
            case RECTANGLE -> "whiteSpace=wrap;html=1;";
            case ROUNDED_RECTANGLE -> "rounded=1;whiteSpace=wrap;html=1;";
            case ELLIPSE -> "ellipse;whiteSpace=wrap;html=1;";
            case DIAMOND -> "rhombus;whiteSpace=wrap;html=1;";
            case CYLINDER -> "shape=cylinder3;whiteSpace=wrap;html=1;";
            case ACTOR -> "shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;";
        };
    }

    private String edgeStyle(ObservedDiagramGraph.EdgeDirection direction,
                             ObservedDiagramGraph.LineStyle lineStyle) {
        String directionStyle = switch (direction) {
            case FORWARD, REVERSE -> "edgeStyle=orthogonalEdgeStyle;rounded=0;endArrow=block;html=1;";
            case BIDIRECTIONAL ->
                    "edgeStyle=orthogonalEdgeStyle;rounded=0;startArrow=block;endArrow=block;html=1;";
            case NONE -> "edgeStyle=orthogonalEdgeStyle;rounded=0;endArrow=none;html=1;";
        };
        return directionStyle + switch (lineStyle) {
            case SOLID -> "";
            case DASHED -> "dashed=1;";
            case DOTTED -> "dashed=1;dashPattern=1 4;";
        };
    }

    private String groupStyle(ObservedDiagramGraph.GroupKind kind) {
        return switch (kind) {
            case GROUP -> "group;";
            case SWIMLANE -> "swimlane;html=1;";
            case CONTAINER -> "container=1;whiteSpace=wrap;html=1;";
        };
    }

    private int scale(double value, int extent) {
        return (int) Math.round(value * extent);
    }

    private String nodeCellId(String id) {
        return DirectDiagramCellIds.node(id);
    }

    private String edgeCellId(String id) {
        return DirectDiagramCellIds.edge(id);
    }

    private String groupCellId(String id) {
        return DirectDiagramCellIds.group(id);
    }

    private String attribute(String value) {
        // Escape every XML attribute boundary character; labels never become markup.
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }
}
