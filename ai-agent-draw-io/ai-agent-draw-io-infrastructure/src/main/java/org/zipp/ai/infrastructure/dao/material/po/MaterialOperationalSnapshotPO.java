package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class MaterialOperationalSnapshotPO {
    private Instant capturedAt;
    private int queuedJobs;
    private int runningJobs;
    private int failedJobsLast24Hours;
    private long oldestQueuedSeconds;
    private int activeReadLeases;
    private int expiredReadLeases;
    private int stuckDeletingMaterials;
    private long oldestDeletionSeconds;
    private long indexedPagesThisMonth;
    private int staleVectorBatches;
    private int openProjectionRepairs;
    private int pendingOrphanDeletions;
    private int purgingGenerations;
}
