package org.zipp.ai.domain.agent.model.valobj.canvas;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;

import java.util.Map;
import java.util.Set;

public record CanvasMutationDecision(
        CanvasMutationStatus status,
        String resultingXml,
        CanvasAnalysis before,
        CanvasAnalysis after,
        Set<String> changedCellIds,
        Map<String, Set<CanvasField>> changedFields,
        CanvasMutationRejectionReason rejectionReason,
        CanvasStateSaveResult saveResult,
        CanvasState currentState
) {
    public CanvasMutationDecision {
        changedCellIds = changedCellIds == null ? Set.of() : Set.copyOf(changedCellIds);
        changedFields = changedFields == null ? Map.of() : Map.copyOf(changedFields);
    }

    public CanvasState savedState() {
        return saveResult == null ? null : saveResult.getState();
    }
}
