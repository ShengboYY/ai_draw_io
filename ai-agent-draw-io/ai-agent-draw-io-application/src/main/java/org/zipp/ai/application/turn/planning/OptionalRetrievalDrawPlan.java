package org.zipp.ai.application.turn.planning;

import java.util.List;

/** Optional grounded primary branch plus the only Plain branch it may enter. */
public record OptionalRetrievalDrawPlan(
        List<String> candidateRefs,
        ValidatedPlainFallback validatedFallback,
        PlanningLineageFingerprint lineage
) {
    public OptionalRetrievalDrawPlan {
        if (candidateRefs == null || candidateRefs.isEmpty()
                || candidateRefs.stream().anyMatch(value -> value == null || value.isBlank())
                || validatedFallback == null || lineage == null) {
            throw new IllegalArgumentException("invalid Optional Retrieval plan");
        }
        candidateRefs = List.copyOf(candidateRefs);
    }
}
