package org.zipp.ai.domain.operations;

import java.time.Instant;
import java.util.Objects;

/** Content-free operational projection used by the capability dashboard and alert rules. */
public record MaterialOperationalSnapshot(Instant capturedAt,
                                          int queuedJobs,
                                          int runningJobs,
                                          int failedJobsLast24Hours,
                                          long oldestQueuedSeconds,
                                          int activeReadLeases,
                                          int expiredReadLeases,
                                          int stuckDeletingMaterials,
                                          long oldestDeletionSeconds,
                                          long indexedPagesThisMonth,
                                          int staleVectorBatches,
                                          int openProjectionRepairs,
                                          int pendingOrphanDeletions,
                                          int purgingGenerations) {
    public MaterialOperationalSnapshot {
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        if (queuedJobs < 0 || runningJobs < 0 || failedJobsLast24Hours < 0
                || oldestQueuedSeconds < 0 || activeReadLeases < 0 || expiredReadLeases < 0
                || stuckDeletingMaterials < 0 || oldestDeletionSeconds < 0
                || indexedPagesThisMonth < 0 || staleVectorBatches < 0 || openProjectionRepairs < 0
                || pendingOrphanDeletions < 0 || purgingGenerations < 0) {
            throw new IllegalArgumentException("operational snapshot values cannot be negative");
        }
    }

    public static MaterialOperationalSnapshot unavailable(Instant capturedAt) {
        return new MaterialOperationalSnapshot(capturedAt, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0);
    }
}
