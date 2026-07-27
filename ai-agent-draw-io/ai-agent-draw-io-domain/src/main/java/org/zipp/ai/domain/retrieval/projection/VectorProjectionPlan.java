package org.zipp.ai.domain.retrieval.projection;

import java.util.List;
import java.util.Objects;

/** Complete generation-scoped plan for one ProcessingRevision. */
public record VectorProjectionPlan(String revisionId, String versionId, String generationId,
                                   String planFingerprint, VectorGenerationProfile profile,
                                   List<VectorProjectionTarget> projections,
                                   List<VectorBatchPlan> batches) {
    public VectorProjectionPlan {
        if (revisionId == null || revisionId.isBlank() || versionId == null || versionId.isBlank()
                || generationId == null || generationId.isBlank()
                || planFingerprint == null || !planFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector projection plan identity is invalid");
        }
        profile = Objects.requireNonNull(profile, "profile");
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
        if (batches.stream().flatMap(batch -> batch.projections().stream()).count() != projections.size()
                || projections.isEmpty() != batches.isEmpty()) {
            throw new IllegalArgumentException("vector projection plan batches are incomplete");
        }
    }
}
