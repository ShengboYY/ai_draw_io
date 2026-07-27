package org.zipp.ai.domain.retrieval.model.valobj;

import java.time.Instant;
import java.util.List;

/** Exact-ID cleanup batch for a RETIRED generation after its rollback boundary. */
public record RetiredGenerationCleanup(String generationId, String namespace, Instant eligibleAt,
                                       List<String> vectorIds) {
    public RetiredGenerationCleanup {
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        generationId = generationId.trim();
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        namespace = namespace.trim();
        if (eligibleAt == null) throw new IllegalArgumentException("eligibleAt is required");
        vectorIds = List.copyOf(vectorIds);
        if (vectorIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("cleanup vector IDs must be non-blank");
        }
    }
}
