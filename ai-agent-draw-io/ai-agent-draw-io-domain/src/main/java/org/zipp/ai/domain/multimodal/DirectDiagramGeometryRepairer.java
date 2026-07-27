package org.zipp.ai.domain.multimodal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Applies a bounded deterministic layout repair without changing observed topology or text. */
final class DirectDiagramGeometryRepairer {
    private static final double SEARCH_STEP = 0.02;
    private static final int MAX_SEARCH_RINGS = 50;
    private static final double MATERIAL_OVERLAP_RATIO = 0.15;
    private static final double MIN_VISIBLE_WIDTH = 1.0 / 1_200;
    private static final double MIN_VISIBLE_HEIGHT = 1.0 / 800;

    RepairResult repair(ObservedDiagramGraph graph) {
        List<ObservedDiagramGraph.Group> repairedGroups = new ArrayList<>();
        boolean changed = false;
        for (ObservedDiagramGraph.Group group : graph.groups()) {
            ObservationBounds visible = visibleBounds(group.bounds(), null);
            ObservationBounds bounds = relocate(visible, 0, 0, 1, 1,
                    candidate -> repairedGroups.stream().noneMatch(
                            placed -> materialOverlap(candidate, placed.bounds())));
            if (bounds == null) return new RepairResult(graph, changed, true);
            changed |= !bounds.equals(group.bounds());
            repairedGroups.add(new ObservedDiagramGraph.Group(
                    group.id(), group.label(), group.kind(), bounds,
                    group.evidenceId(), group.confidence()));
        }
        Map<String, ObservedDiagramGraph.Group> originalGroups = graph.groups().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ObservedDiagramGraph.Group::id, group -> group));
        Map<String, ObservedDiagramGraph.Group> groups = repairedGroups.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ObservedDiagramGraph.Group::id, group -> group));
        List<ObservedDiagramGraph.Node> repaired = new ArrayList<>();
        Set<String> movedNodeIds = new LinkedHashSet<>();
        for (ObservedDiagramGraph.Node node : graph.nodes()) {
            ObservationBounds translated = translatedBounds(
                    node.bounds(), originalGroups.get(node.groupId()), groups.get(node.groupId()));
            ObservationBounds bounds = repairedBounds(
                    translated, repaired, groups.get(node.groupId()));
            if (bounds == null) return new RepairResult(graph, changed, true);
            if (!bounds.equals(node.bounds())) {
                changed = true;
                movedNodeIds.add(node.id());
            }
            repaired.add(new ObservedDiagramGraph.Node(
                    node.id(), node.label(), node.shape(), bounds, node.groupId(),
                    node.evidenceId(), node.confidence()));
        }
        List<ObservedDiagramGraph.Edge> repairedEdges = graph.edges().stream()
                .map(edge -> movedNodeIds.contains(edge.sourceId())
                        || movedNodeIds.contains(edge.targetId())
                        ? new ObservedDiagramGraph.Edge(
                                edge.id(), edge.sourceId(), edge.targetId(), edge.label(),
                                edge.direction(), edge.lineStyle(), List.of(),
                                edge.evidenceId(), edge.confidence())
                        : edge)
                .toList();
        return new RepairResult(new ObservedDiagramGraph(
                repaired, repairedEdges, repairedGroups, graph.unresolvedItems()), changed, false);
    }

    private ObservationBounds repairedBounds(
            ObservationBounds observed,
            List<ObservedDiagramGraph.Node> placed,
            ObservedDiagramGraph.Group group) {
        ObservationBounds visible = visibleBounds(
                observed, group == null ? null : group.bounds());
        if (withinContainer(visible, group)
                && available(visible, placed)) {
            return visible;
        }
        double minX = group == null ? 0 : group.bounds().x();
        double minY = group == null ? 0 : group.bounds().y();
        double maxX = group == null ? 1 : group.bounds().x() + group.bounds().width();
        double maxY = group == null ? 1 : group.bounds().y() + group.bounds().height();
        return relocate(visible, minX, minY, maxX, maxY,
                candidate -> available(candidate, placed));
    }

    private ObservationBounds relocate(
            ObservationBounds initial,
            double minX, double minY, double maxX, double maxY,
            Predicate<ObservationBounds> available) {
        if (initial.x() >= minX && initial.y() >= minY
                && initial.x() + initial.width() <= maxX
                && initial.y() + initial.height() <= maxY
                && available.test(initial)) {
            return initial;
        }
        for (int ring = 1; ring <= MAX_SEARCH_RINGS; ring++) {
            double offset = ring * SEARCH_STEP;
            for (double[] delta : List.of(
                    new double[]{0, offset}, new double[]{offset, 0},
                    new double[]{0, -offset}, new double[]{-offset, 0},
                    new double[]{offset, offset}, new double[]{-offset, offset},
                    new double[]{offset, -offset}, new double[]{-offset, -offset})) {
                double x = initial.x() + delta[0];
                double y = initial.y() + delta[1];
                if (x < minX || y < minY
                        || x + initial.width() > maxX
                        || y + initial.height() > maxY) {
                    continue;
                }
                ObservationBounds candidate = new ObservationBounds(
                        x, y, initial.width(), initial.height());
                if (available.test(candidate)) return candidate;
            }
        }
        return null;
    }

    private ObservationBounds translatedBounds(
            ObservationBounds bounds,
            ObservedDiagramGraph.Group originalGroup,
            ObservedDiagramGraph.Group repairedGroup) {
        if (originalGroup == null || repairedGroup == null) return bounds;
        double x = Math.max(0, Math.min(1 - bounds.width(),
                bounds.x() + repairedGroup.bounds().x() - originalGroup.bounds().x()));
        double y = Math.max(0, Math.min(1 - bounds.height(),
                bounds.y() + repairedGroup.bounds().y() - originalGroup.bounds().y()));
        return new ObservationBounds(x, y, bounds.width(), bounds.height());
    }

    private ObservationBounds visibleBounds(ObservationBounds bounds,
                                            ObservationBounds container) {
        double minX = container == null ? 0 : container.x();
        double minY = container == null ? 0 : container.y();
        double maxX = container == null ? 1 : container.x() + container.width();
        double maxY = container == null ? 1 : container.y() + container.height();
        double width = Math.min(maxX - minX, Math.max(bounds.width(), MIN_VISIBLE_WIDTH));
        double height = Math.min(maxY - minY, Math.max(bounds.height(), MIN_VISIBLE_HEIGHT));
        double x = Math.max(minX, Math.min(bounds.x(), maxX - width));
        double y = Math.max(minY, Math.min(bounds.y(), maxY - height));
        return new ObservationBounds(x, y, width, height);
    }

    private boolean withinContainer(ObservationBounds bounds,
                                    ObservedDiagramGraph.Group group) {
        if (group == null) return true;
        ObservationBounds container = group.bounds();
        return bounds.x() >= container.x() && bounds.y() >= container.y()
                && bounds.x() + bounds.width() <= container.x() + container.width()
                && bounds.y() + bounds.height() <= container.y() + container.height();
    }

    private boolean available(ObservationBounds candidate,
                              List<ObservedDiagramGraph.Node> placed) {
        return placed.stream()
                .noneMatch(node -> materialOverlap(candidate, node.bounds()));
    }

    private boolean materialOverlap(ObservationBounds left, ObservationBounds right) {
        double width = Math.max(0, Math.min(left.x() + left.width(), right.x() + right.width())
                - Math.max(left.x(), right.x()));
        double height = Math.max(0, Math.min(left.y() + left.height(), right.y() + right.height())
                - Math.max(left.y(), right.y()));
        double overlap = width * height;
        double smaller = Math.min(left.width() * left.height(), right.width() * right.height());
        return smaller > 0 && overlap / smaller > MATERIAL_OVERLAP_RATIO;
    }

    record RepairResult(ObservedDiagramGraph graph, boolean changed, boolean unresolved) {}
}
