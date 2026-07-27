package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

/** Immutable result consumed by later direct-source and retrieval execution modules. */
public record TaskSourcePlan(CanvasAction action, SourceUse sourceUse, SourceMode retrievalMode,
                             String primaryDirectVersionId,
                             List<String> selectedReferenceVersionIds, boolean strict,
                             boolean requiresVisualObservation, String clarificationReason,
                             String rejectionReason) {
    public TaskSourcePlan(CanvasAction action, SourceUse sourceUse, SourceMode retrievalMode,
                          String primaryDirectVersionId,
                          List<String> selectedReferenceVersionIds, boolean strict,
                          boolean requiresVisualObservation, String clarificationReason) {
        this(action, sourceUse, retrievalMode, primaryDirectVersionId, selectedReferenceVersionIds,
                strict, requiresVisualObservation, clarificationReason, "");
    }

    public TaskSourcePlan {
        primaryDirectVersionId = primaryDirectVersionId == null ? "" : primaryDirectVersionId.trim();
        selectedReferenceVersionIds = List.copyOf(selectedReferenceVersionIds == null ? List.of()
                : selectedReferenceVersionIds);
        clarificationReason = clarificationReason == null ? "" : clarificationReason.trim();
        rejectionReason = rejectionReason == null ? "" : rejectionReason.trim();
        if (!clarificationReason.isEmpty() && !rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("a source plan cannot clarify and reject simultaneously");
        }
        if (!clarificationReason.isEmpty()) {
            primaryDirectVersionId = "";
            requiresVisualObservation = false;
        }
        if (!rejectionReason.isEmpty()) {
            primaryDirectVersionId = "";
            requiresVisualObservation = false;
        }
    }

    public boolean needsClarification() {
        return !clarificationReason.isEmpty();
    }

    public boolean rejected() {
        return !rejectionReason.isEmpty();
    }

    public static TaskSourcePlan rejected(CanvasAction action, SourceUse sourceUse, String reason) {
        return new TaskSourcePlan(action, sourceUse, SourceMode.NONE, "", List.of(), false,
                false, "", reason);
    }
}
