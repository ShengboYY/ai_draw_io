package org.zipp.ai.domain.retrieval.model.valobj;

import java.util.List;

/** Authoritative MySQL inventory for one generation-scoped vector batch. */
public record ProjectionBatchInventory(String generationId, String revisionId, int batchNo,
                                       String batchInputFingerprint, List<String> vectorIds) {
    public ProjectionBatchInventory {
        generationId = required(generationId, "generationId");
        revisionId = required(revisionId, "revisionId");
        if (batchNo < 0) throw new IllegalArgumentException("batchNo must be non-negative");
        if (batchInputFingerprint == null || !batchInputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("batchInputFingerprint must be lowercase SHA-256");
        }
        vectorIds = List.copyOf(vectorIds);
        if (vectorIds.isEmpty() || vectorIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("projection batch vector IDs are required");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
