package org.zipp.ai.domain.agent.service.visualreview;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;

import java.util.List;
import java.util.function.Predicate;

public class CanvasVisualReviewManifestProjector {

    private static final int MAX_NODES = 100;
    private static final int MAX_EDGES = 100;

    public Projection project(List<CanvasCellData> cells) {
        List<CanvasCellData> eligible = cells == null ? List.of() : cells.stream()
                .filter(cell -> cell != null && StringUtils.isNotBlank(cell.getId()))
                .toList();
        Predicate<CanvasCellData> isEdge = cell -> "edge".equalsIgnoreCase(cell.getKind());
        List<CanvasCellData> allNodes = eligible.stream().filter(isEdge.negate()).toList();
        List<CanvasCellData> allEdges = eligible.stream().filter(isEdge).toList();
        return new Projection(
                allNodes.stream().limit(MAX_NODES).toList(),
                allEdges.stream().limit(MAX_EDGES).toList(),
                allNodes.size(),
                allEdges.size());
    }

    public record Projection(
            List<CanvasCellData> nodes,
            List<CanvasCellData> edges,
            int totalNodeCount,
            int totalEdgeCount
    ) {
        public Projection {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }

        public int truncatedNodeCount() {
            return Math.max(0, totalNodeCount - nodes.size());
        }

        public int truncatedEdgeCount() {
            return Math.max(0, totalEdgeCount - edges.size());
        }
    }
}
