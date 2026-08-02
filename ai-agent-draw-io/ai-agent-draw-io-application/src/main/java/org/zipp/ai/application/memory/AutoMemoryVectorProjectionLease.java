package org.zipp.ai.application.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fenced snapshot of one Memory and its previously published vector identities. */
public record AutoMemoryVectorProjectionLease(
        String memoryId,
        String workerId,
        long version,
        long desiredRevision,
        int attemptCount,
        List<AutoMemoryVectorDocument> documents,
        Set<String> projectedVectorIds
) {
    public AutoMemoryVectorProjectionLease {
        memoryId = required(memoryId, "memoryId");
        workerId = required(workerId, "workerId");
        if (version < 1 || desiredRevision < 1 || attemptCount < 1) {
            throw new IllegalArgumentException("projection lease identity is invalid");
        }
        documents = documents == null ? List.of() : List.copyOf(documents);
        projectedVectorIds = projectedVectorIds == null
                ? Set.of() : Set.copyOf(projectedVectorIds);
        Set<String> desiredIds = new HashSet<>();
        for (AutoMemoryVectorDocument document : documents) {
            if (!memoryId.equals(document.memoryId())
                    || desiredRevision != document.projectionRevision()
                    || !desiredIds.add(document.vectorId())) {
                throw new IllegalArgumentException("projection documents violate the lease fence");
            }
        }
    }

    public Set<String> desiredVectorIds() {
        return documents.stream()
                .map(AutoMemoryVectorDocument::vectorId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
