package org.zipp.ai.api.dto;

import java.time.Instant;
import java.util.Map;

public record MaterialCapabilityReportDTO(Map<String, String> capabilities,
                                          String overallMaterialState,
                                          String capacityLevel,
                                          double capacityUsagePercent,
                                          OperationsDTO operations,
                                          boolean releaseApproved,
                                          String releaseReportVersion) {
    public record OperationsDTO(Instant capturedAt, int queuedJobs, int runningJobs,
                                int failedJobsLast24Hours, long oldestQueuedSeconds,
                                int activeReadLeases, int expiredReadLeases,
                                int stuckDeletingMaterials, long oldestDeletionSeconds,
                                long indexedPagesThisMonth, int staleVectorBatches,
                                int openProjectionRepairs, int pendingOrphanDeletions,
                                int purgingGenerations) { }
}
