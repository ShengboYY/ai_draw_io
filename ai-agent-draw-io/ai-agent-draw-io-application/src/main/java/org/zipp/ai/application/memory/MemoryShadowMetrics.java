package org.zipp.ai.application.memory;

/** Counts used to evaluate shadow precision without writing candidate or Memory rows. */
public record MemoryShadowMetrics(
        int acceptedCount,
        int duplicateCount,
        int conflictCount,
        int rejectedCount,
        int staleEventCount
) {
    public MemoryShadowMetrics {
        if (acceptedCount < 0 || duplicateCount < 0 || conflictCount < 0
                || rejectedCount < 0 || staleEventCount < 0) {
            throw new IllegalArgumentException("shadow metrics cannot be negative");
        }
    }
}
