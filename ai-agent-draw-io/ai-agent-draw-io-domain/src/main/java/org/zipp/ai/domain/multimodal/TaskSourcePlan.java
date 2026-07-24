package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

/** Immutable result consumed by later direct-source and retrieval execution modules. */
public record TaskSourcePlan(CanvasAction action, SourceUse sourceUse, SourceMode retrievalMode,
                             String primaryDirectVersionId,
                             List<String> selectedReferenceVersionIds, boolean strict,
                             boolean requiresVisualObservation, String clarificationReason) {
    public TaskSourcePlan {
        primaryDirectVersionId = primaryDirectVersionId == null ? "" : primaryDirectVersionId.trim();
        selectedReferenceVersionIds = List.copyOf(selectedReferenceVersionIds == null ? List.of()
                : selectedReferenceVersionIds);
        clarificationReason = clarificationReason == null ? "" : clarificationReason.trim();
        if (!clarificationReason.isEmpty()) {
            primaryDirectVersionId = "";
            requiresVisualObservation = false;
        }
    }

    public boolean needsClarification() {
        return !clarificationReason.isEmpty();
    }
}
