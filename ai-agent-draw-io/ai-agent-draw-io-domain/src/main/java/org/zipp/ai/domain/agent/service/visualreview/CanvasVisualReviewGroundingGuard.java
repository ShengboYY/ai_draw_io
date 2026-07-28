package org.zipp.ai.domain.agent.service.visualreview;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasRepairScope;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewGrounding;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CanvasVisualReviewGroundingGuard {

    private final CanvasVisualReviewManifestProjector manifestProjector =
            new CanvasVisualReviewManifestProjector();

    public CanvasVisualReviewGrounding ground(CanvasAnalysis analysis, CanvasVisualReviewResult result) {
        return ground(
                analysis == null || analysis.getCells() == null
                        ? List.of()
                        : analysis.getCells(),
                result);
    }

    public CanvasVisualReviewGrounding ground(
            List<CanvasCellData> cells,
            CanvasVisualReviewResult result
    ) {
        List<CanvasCellData> safeCells = List.copyOf(cells == null ? List.of() : cells);
        Map<String, CanvasCellData> allCellsById = safeCells.stream()
                .filter(cell -> cell != null && StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        CanvasVisualReviewManifestProjector.Projection projection =
                manifestProjector.project(safeCells);
        Map<String, CanvasCellData> manifestCellsById = java.util.stream.Stream
                .concat(projection.nodes().stream(), projection.edges().stream())
                .collect(Collectors.toMap(CanvasCellData::getId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        int nodeCount = projection.nodes().size();
        int edgeCount = projection.edges().size();
        Set<String> returnedTargets = new LinkedHashSet<>();
        Set<String> validTargets = new LinkedHashSet<>();
        Set<String> invalidTargets = new LinkedHashSet<>();
        Set<CanvasField> allowedFields = new LinkedHashSet<>();
        Set<Set<CanvasField>> requiredCapabilities = new LinkedHashSet<>();
        String conflict = null;

        for (CanvasVisualIssue issue : result == null ? List.<CanvasVisualIssue>of() : result.safeIssues()) {
            if (issue == null) continue;
            List<String> issueTargets = issue.getTargetCellIds() == null ? List.of() : issue.getTargetCellIds().stream()
                    .filter(StringUtils::isNotBlank).distinct().toList();
            Set<CanvasField> issueFields = fieldsFor(issue.getType());
            returnedTargets.addAll(issueTargets);
            if (issue.getRepairScope() == CanvasVisualRepairScope.WHOLE_CANVAS && conflict == null) {
                conflict = "whole_canvas_not_automatable";
            }
            if (issue.getRepairScope() == CanvasVisualRepairScope.LOCAL
                    && !issueFields.isEmpty() && issueTargets.isEmpty() && conflict == null) {
                // Visible labels remain explanatory evidence and never grant mutation authority.
                conflict = "local_issue_without_target";
            }

            Set<CanvasCellData> resolved = new LinkedHashSet<>();
            boolean unknownTarget = false;
            boolean outsideManifest = false;
            for (String targetId : issueTargets) {
                CanvasCellData cell = manifestCellsById.get(targetId);
                if (cell == null) {
                    invalidTargets.add(targetId);
                    if (allCellsById.containsKey(targetId)) outsideManifest = true;
                    else unknownTarget = true;
                }
                else {
                    validTargets.add(targetId);
                    resolved.add(cell);
                }
            }
            if (unknownTarget && conflict == null) {
                conflict = "unknown_target_cell";
            } else if (outsideManifest && conflict == null) {
                conflict = "target_not_in_manifest";
            }
            if (issue.getType() == CanvasVisualIssueType.EDGE_TRACEABILITY && conflict == null) {
                if (resolved.stream().noneMatch(this::isEdge)) {
                    conflict = "edge_issue_without_edge_target";
                } else if (resolved.stream().anyMatch(cell -> !isEdge(cell))) {
                    conflict = "edge_issue_with_non_edge_target";
                }
            }
            if (!issueFields.isEmpty()) {
                requiredCapabilities.add(Set.copyOf(issueFields));
                allowedFields.addAll(issueFields);
            }
        }
        if (conflict == null && requiredCapabilities.size() > 1) {
            // The current authorization contract has global fields, so mixed capabilities would over-grant targets.
            conflict = "mixed_target_capabilities";
        }

        CanvasMutationAuthorization authorization = conflict == null
                ? new CanvasMutationAuthorization(validTargets, allowedFields, CanvasRepairScope.TARGET_CELLS)
                : new CanvasMutationAuthorization(Set.of(), Set.of(), CanvasRepairScope.TARGET_CELLS);
        return new CanvasVisualReviewGrounding(
                authorization, nodeCount, edgeCount, returnedTargets.size(), validTargets.size(),
                invalidTargets.size(), StringUtils.defaultString(conflict));
    }

    private Set<CanvasField> fieldsFor(CanvasVisualIssueType type) {
        if (type == null) return Set.of();
        return switch (type) {
            case EDGE_TRACEABILITY -> EnumSet.of(
                    CanvasField.STYLE, CanvasField.WAYPOINTS, CanvasField.SOURCE_TARGET);
            case TEXT_READABILITY -> EnumSet.of(CanvasField.STYLE, CanvasField.GEOMETRY);
            case LAYOUT_HIERARCHY -> EnumSet.of(CanvasField.GEOMETRY, CanvasField.WAYPOINTS);
            case STYLE_COHERENCE -> EnumSet.of(CanvasField.STYLE);
            default -> Set.of();
        };
    }

    private boolean isEdge(CanvasCellData cell) {
        return cell != null && "edge".equalsIgnoreCase(cell.getKind());
    }
}
