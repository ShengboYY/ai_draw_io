package org.zipp.ai.application.memory;

import java.time.Instant;

/** Materialized automatic Memory returned by persistence and management boundaries. */
public record AutoMemory(
        String memoryId,
        AutoMemoryScope scope,
        AutoMemoryType type,
        String semanticKey,
        String title,
        String canonicalText,
        AutoMemoryStatus status,
        double confidence,
        int evidenceCount,
        boolean explicit,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public AutoMemory {
        required(memoryId, "memoryId");
        if (scope == null || type == null || status == null) {
            throw new IllegalArgumentException("scope, type and status must not be null");
        }
        required(semanticKey, "semanticKey");
        required(title, "title");
        required(canonicalText, "canonicalText");
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (evidenceCount < 1 || version < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("invalid Memory counters or timestamps");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
