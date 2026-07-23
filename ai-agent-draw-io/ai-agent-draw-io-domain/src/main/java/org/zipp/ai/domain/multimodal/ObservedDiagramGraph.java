package org.zipp.ai.domain.multimodal;

import java.util.List;
import java.util.Objects;

/**
 * Explicit topology observed from one image. Relationships must be supplied by the
 * observation provider; this model deliberately does not infer endpoints from labels.
 */
public record ObservedDiagramGraph(List<Node> nodes, List<Edge> edges, List<Group> groups,
                                   List<String> unresolvedItems) {
    public ObservedDiagramGraph {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        edges = List.copyOf(edges == null ? List.of() : edges);
        groups = List.copyOf(groups == null ? List.of() : groups);
        unresolvedItems = List.copyOf(unresolvedItems == null ? List.of() : unresolvedItems.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
    }

    public record Node(String id, String label, Shape shape, ObservationBounds bounds,
                       String groupId, String evidenceId, double confidence) {
        public Node {
            id = required(id, "node id");
            label = required(label, "node label");
            shape = Objects.requireNonNull(shape, "node shape");
            bounds = Objects.requireNonNull(bounds, "node bounds");
            groupId = optional(groupId);
            evidenceId = required(evidenceId, "node evidenceId");
            confidence = validateConfidence(confidence);
        }
    }

    public record Edge(String id, String sourceId, String targetId, String label,
                       EdgeDirection direction, List<Point> waypoints,
                       String evidenceId, double confidence) {
        public Edge {
            id = required(id, "edge id");
            sourceId = required(sourceId, "edge sourceId");
            targetId = required(targetId, "edge targetId");
            label = optional(label);
            direction = Objects.requireNonNull(direction, "edge direction");
            waypoints = List.copyOf(waypoints == null ? List.of() : waypoints);
            evidenceId = required(evidenceId, "edge evidenceId");
            confidence = validateConfidence(confidence);
        }
    }

    public record Group(String id, String label, ObservationBounds bounds,
                        String evidenceId, double confidence) {
        public Group {
            id = required(id, "group id");
            label = required(label, "group label");
            bounds = Objects.requireNonNull(bounds, "group bounds");
            evidenceId = required(evidenceId, "group evidenceId");
            confidence = validateConfidence(confidence);
        }
    }

    public record Point(double x, double y) {
        public Point {
            if (!finite(x) || !finite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
                throw new IllegalArgumentException("waypoint must fit normalized image coordinates");
            }
        }
    }

    public enum Shape {
        RECTANGLE,
        ROUNDED_RECTANGLE,
        ELLIPSE,
        DIAMOND,
        CYLINDER,
        ACTOR
    }

    public enum EdgeDirection {
        FORWARD,
        REVERSE,
        BIDIRECTIONAL,
        NONE
    }

    private static String required(String value, String field) {
        String normalized = optional(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static double validateConfidence(double value) {
        if (!finite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        return value;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
