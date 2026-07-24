package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;
import java.util.Objects;

/** Content-free, server-verified inputs to {@link TaskSourcePlanner}. */
public record TaskSourcePlanningCommand(CanvasAction action, SourceUse requestedSourceUse,
                                        SourceMode retrievalMode, List<String> directCandidateVersionIds,
                                        List<String> newlyUploadedDirectCandidateVersionIds,
                                        List<String> conversationDirectCandidateVersionIds,
                                        String namedDirectCandidateVersionId,
                                        List<String> selectedReferenceVersionIds,
                                        int pendingSourceCount) {
    public TaskSourcePlanningCommand {
        Objects.requireNonNull(action, "action");
        requestedSourceUse = requestedSourceUse == null ? SourceUse.NONE : requestedSourceUse;
        retrievalMode = retrievalMode == null ? SourceMode.AUTO : retrievalMode;
        directCandidateVersionIds = ids(directCandidateVersionIds);
        newlyUploadedDirectCandidateVersionIds = ids(newlyUploadedDirectCandidateVersionIds).stream()
                .filter(directCandidateVersionIds::contains).toList();
        conversationDirectCandidateVersionIds = ids(conversationDirectCandidateVersionIds).stream()
                .filter(directCandidateVersionIds::contains).toList();
        namedDirectCandidateVersionId = id(namedDirectCandidateVersionId);
        if (!directCandidateVersionIds.contains(namedDirectCandidateVersionId)) {
            namedDirectCandidateVersionId = "";
        }
        selectedReferenceVersionIds = ids(selectedReferenceVersionIds);
        pendingSourceCount = Math.max(0, pendingSourceCount);
    }

    private static List<String> ids(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }

    private static String id(String value) {
        return value == null ? "" : value.trim();
    }
}
