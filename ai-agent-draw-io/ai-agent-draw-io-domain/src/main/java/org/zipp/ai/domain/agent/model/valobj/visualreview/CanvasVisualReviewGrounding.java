package org.zipp.ai.domain.agent.model.valobj.visualreview;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;

public record CanvasVisualReviewGrounding(
        CanvasMutationAuthorization authorization,
        int nodeCount,
        int edgeCount,
        int returnedTargetCount,
        int validTargetCount,
        int invalidTargetCount,
        String conflictReason
) {
    public boolean hasConflict() {
        return conflictReason != null && !conflictReason.isBlank();
    }
}
