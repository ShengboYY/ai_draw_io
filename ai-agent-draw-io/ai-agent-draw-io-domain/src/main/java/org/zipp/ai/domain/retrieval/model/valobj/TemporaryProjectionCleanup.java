package org.zipp.ai.domain.retrieval.model.valobj;

import java.util.List;

/** Exact current-generation vector IDs that belong only to one expired Conversation material. */
public record TemporaryProjectionCleanup(String generationId, String materialId,
                                         List<String> vectorIds) {
    public TemporaryProjectionCleanup {
        generationId = required(generationId, "generationId");
        materialId = required(materialId, "materialId");
        vectorIds = List.copyOf(vectorIds);
        if (vectorIds.isEmpty() || vectorIds.size() > 100
                || vectorIds.stream().anyMatch(id -> id == null || id.isBlank())
                || vectorIds.stream().distinct().count() != vectorIds.size()) {
            throw new IllegalArgumentException("cleanup vector IDs must be 1-100 unique non-blank IDs");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
