package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;
import java.util.Objects;

/** Content-free, server-verified inputs to {@link TaskSourcePlanner}. */
public record TaskSourcePlanningCommand(CanvasAction action, SourceUse requestedSourceUse,
                                        SourceMode retrievalMode, List<String> readyAttachmentVersionIds,
                                        List<String> selectedReferenceVersionIds,
                                        int pendingAttachmentCount, boolean hasSingleReadyImageAttachment) {
    public TaskSourcePlanningCommand {
        Objects.requireNonNull(action, "action");
        requestedSourceUse = requestedSourceUse == null ? SourceUse.NONE : requestedSourceUse;
        retrievalMode = retrievalMode == null ? SourceMode.AUTO : retrievalMode;
        readyAttachmentVersionIds = ids(readyAttachmentVersionIds);
        selectedReferenceVersionIds = ids(selectedReferenceVersionIds);
        pendingAttachmentCount = Math.max(0, pendingAttachmentCount);
        // A direct reconstruction is intentionally restricted to one exact, ready image attachment.
        hasSingleReadyImageAttachment = hasSingleReadyImageAttachment && readyAttachmentVersionIds.size() == 1;
    }

    private static List<String> ids(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }
}
