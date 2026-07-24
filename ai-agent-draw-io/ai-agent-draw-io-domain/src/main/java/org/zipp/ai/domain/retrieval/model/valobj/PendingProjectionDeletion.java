package org.zipp.ai.domain.retrieval.model.valobj;

import java.util.List;

/** Provider deletion claim routed by its persisted generation namespace. */
public record PendingProjectionDeletion(String generationId, String namespace,
                                        List<String> vectorIds) {
    public PendingProjectionDeletion {
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        generationId = generationId.trim();
        namespace = namespace.trim();
        vectorIds = List.copyOf(vectorIds);
        if (vectorIds.isEmpty() || vectorIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("pending vector IDs must be non-empty");
        }
    }
}
