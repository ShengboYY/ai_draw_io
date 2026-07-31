package org.zipp.ai.api.dto;

import java.time.Instant;

/** User-visible automatic Memory view without tenant-internal owner data. */
public record AutoMemoryResponseDTO(
        String memoryId,
        String scopeType,
        String scopeKey,
        String memoryType,
        String semanticKey,
        String title,
        String canonicalText,
        String status,
        double confidence,
        int evidenceCount,
        boolean explicit,
        long version,
        Instant updatedAt
) {
}
