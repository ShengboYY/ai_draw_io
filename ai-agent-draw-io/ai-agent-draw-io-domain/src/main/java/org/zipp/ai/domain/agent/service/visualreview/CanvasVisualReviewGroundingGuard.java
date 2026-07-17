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

    private static final Set<CanvasVisualIssueType> AUTOMATIC_TYPES = EnumSet.of(
            CanvasVisualIssueType.TEXT_READABILITY,
            CanvasVisualIssueType.LAYOUT_HIERARCHY,
            CanvasVisualIssueType.EDGE_TRACEABILITY,
            CanvasVisualIssueType.STYLE_COHERENCE);

    public CanvasVisualReviewGrounding ground(CanvasAnalysis analysis, CanvasVisualReviewResult result) {
        List<CanvasCellData> cells = analysis == null || analysis.getCells() == null
                ? List.of() : analysis.getCells();
        Map<String, CanvasCellData> cellsById = cells.stream()
                .filter(cell -> cell != null && StringUtils.isNotBlank(cell.getId()))
                .collect(Collectors.toMap(CanvasCellData::getId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        int edgeCount = (int) cellsById.values().stream().filter(this::isEdge).count();
        int nodeCount = cellsById.size() - edgeCount;
        Set<String> returnedTargets = new LinkedHashSet<>();
        Set<String> validTargets = new LinkedHashSet<>();
        Set<String> invalidTargets = new LinkedHashSet<>();
        Set<CanvasField> allowedFields = new LinkedHashSet<>();
        String conflict = null;

        for (CanvasVisualIssue issue : result == null ? List.<CanvasVisualIssue>of() : result.safeIssues()) {
            if (issue == null) continue;
            List<String> issueTargets = issue.getTargetCellIds() == null ? List.of() : issue.getTargetCellIds().stream()
                    .filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
            returnedTargets.addAll(issueTargets);
            if (issue.getRepairScope() == CanvasVisualRepairScope.WHOLE_CANVAS && conflict == null) {
                conflict = "whole_canvas_not_automatable";
            }
            if (issue.getRepairScope() == CanvasVisualRepairScope.LOCAL
                    && AUTOMATIC_TYPES.contains(issue.getType()) && issueTargets.isEmpty() && conflict == null) {
                // Visible labels remain explanatory evidence and never grant mutation authority.
                conflict = "local_issue_without_target";
            }

            Set<CanvasCellData> resolved = new LinkedHashSet<>();
            for (String targetId : issueTargets) {
                CanvasCellData cell = cellsById.get(targetId);
                if (cell == null) invalidTargets.add(targetId);
                else {
                    validTargets.add(targetId);
                    resolved.add(cell);
                }
            }
            if (!invalidTargets.isEmpty() && conflict == null) {
                conflict = "unknown_target_cell";
            }
            if (issue.getType() == CanvasVisualIssueType.EDGE_TRACEABILITY
                    && resolved.stream().noneMatch(this::isEdge) && conflict == null) {
                conflict = "edge_issue_without_edge_target";
            }
            allowedFields.addAll(fieldsFor(issue.getType()));
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
            case EDGE_TRACEABILITY -> EnumSet.of(CanvasField.STYLE, CanvasField.WAYPOINTS);
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
