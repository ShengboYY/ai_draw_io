package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

/** Immutable result consumed by later direct-source and retrieval execution modules. */
public record TaskSourcePlan(CanvasAction action, SourceUse sourceUse, SourceMode retrievalMode,
                             List<String> directAttachmentVersionIds,
                             List<String> selectedReferenceVersionIds, boolean strict,
                             boolean requiresVisualObservation) {
    public TaskSourcePlan {
        directAttachmentVersionIds = List.copyOf(directAttachmentVersionIds == null ? List.of()
                : directAttachmentVersionIds);
        selectedReferenceVersionIds = List.copyOf(selectedReferenceVersionIds == null ? List.of()
                : selectedReferenceVersionIds);
    }
}
