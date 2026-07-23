package org.zipp.ai.domain.grounding;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.citation.model.valobj.CitationBinding;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Rejects deterministic semantic collisions between immutable direct cells and retrieved additions. */
final class DirectSourceConflictPolicy {
    private static final double OCCLUSION_RATIO = 0.50D;

    List<String> conflicts(CanvasMutationDecision mutation,
                           Set<String> immutableCellIds,
                           List<CitationBinding> bindings) {
        if (mutation.before() == null || mutation.after() == null || immutableCellIds.isEmpty()) {
            return List.of();
        }
        Map<String, CanvasCellData> before = index(mutation.before().getCells());
        Map<String, CanvasCellData> after = index(mutation.after().getCells());
        Set<String> boundCellIds = bindings.stream()
                .map(CitationBinding::cellId)
                .collect(java.util.stream.Collectors.toSet());
        // Inspect every added cell, including decorative cells that strict citation validation
        // intentionally does not classify as factual.
        Set<String> addedCellIds = after.keySet().stream()
                .filter(id -> !before.containsKey(id))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<CanvasCellData> directNodes = immutableCellIds.stream()
                .map(before::get).filter(this::isNode).toList();
        List<CanvasCellData> directEdges = immutableCellIds.stream()
                .map(before::get).filter(this::isEdge).toList();
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        for (String cellId : addedCellIds) {
            CanvasCellData added = after.get(cellId);
            if (isNode(added)) {
                if (!boundCellIds.contains(cellId)) {
                    conflicts.add("RETRIEVED_CELL_UNBOUND");
                }
                if (directNodes.stream().anyMatch(direct ->
                        !label(added).isEmpty() && label(added).equals(label(direct)))) {
                    conflicts.add("DIRECT_LABEL_CONFLICT");
                }
                if (directNodes.stream().anyMatch(direct -> occludes(added, direct))) {
                    conflicts.add("DIRECT_GEOMETRY_CONFLICT");
                }
            } else if (isEdge(added)) {
                if (!boundCellIds.contains(cellId)) {
                    conflicts.add("RETRIEVED_EDGE_UNBOUND");
                }
                if (directEdges.stream().anyMatch(direct -> sameRelationship(added, direct))) {
                    conflicts.add("DIRECT_RELATION_CONFLICT");
                }
            }
        }
        return List.copyOf(conflicts);
    }

    private Map<String, CanvasCellData> index(List<CanvasCellData> cells) {
        LinkedHashMap<String, CanvasCellData> indexed = new LinkedHashMap<>();
        if (cells != null) for (CanvasCellData cell : cells) indexed.put(cell.getId(), cell);
        return indexed;
    }

    private boolean isNode(CanvasCellData cell) {
        return cell != null && "node".equalsIgnoreCase(cell.getKind());
    }

    private boolean isEdge(CanvasCellData cell) {
        return cell != null && "edge".equalsIgnoreCase(cell.getKind());
    }

    private String label(CanvasCellData cell) {
        if (cell == null || cell.getLabel() == null) return "";
        return cell.getLabel().replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private boolean occludes(CanvasCellData added, CanvasCellData direct) {
        double width = Math.min(added.maxX(), direct.maxX()) - Math.max(added.getX(), direct.getX());
        double height = Math.min(added.maxY(), direct.maxY()) - Math.max(added.getY(), direct.getY());
        if (width <= 0D || height <= 0D) return false;
        double smallerArea = Math.min(
                added.getWidth() * added.getHeight(),
                direct.getWidth() * direct.getHeight());
        return smallerArea > 0D && width * height / smallerArea >= OCCLUSION_RATIO;
    }

    private boolean sameRelationship(CanvasCellData left, CanvasCellData right) {
        return hasEndpoints(left) && hasEndpoints(right)
                && left.getSource().equals(right.getSource())
                && left.getTarget().equals(right.getTarget())
                && label(left).equals(label(right));
    }

    private boolean hasEndpoints(CanvasCellData cell) {
        return cell.getSource() != null && !cell.getSource().isBlank()
                && cell.getTarget() != null && !cell.getTarget().isBlank();
    }
}
