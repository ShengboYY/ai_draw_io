package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.retrieval.model.valobj.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable boundary for reconciliation, audited repair, and retired-generation cleanup. */
public interface IndexProjectionMaintenancePort {
    List<ProjectionBatchInventory> findBatchesDue(String generationId, Instant dueBefore, int limit);
    boolean markBatchChecked(ProjectionBatchInventory batch, Instant checkedAt);
    boolean scheduleMissingVectorRepair(ProjectionBatchInventory batch, Set<String> missingVectorIds,
                                        Instant requestedAt);
    Optional<VectorRepairWork> findRepairWork(String revisionId, String workKey, WorkerFence fence);
    boolean completeRepair(VectorRepairWork work, WorkerFence fence, Instant completedAt);
    String findProviderCursor(String generationId, Instant initializedAt);
    boolean advanceProviderCursor(String generationId, String expectedCursor, String nextCursor,
                                  Instant updatedAt);
    Set<String> findKnownVectorIds(List<String> providerVectorIds);
    String recordOrphanDeletionIntent(String generationId, List<String> vectorIds, Instant requestedAt);
    boolean completeOrphanDeletion(String deletionId, Instant completedAt);
    Optional<RetiredGenerationCleanup> findRetiredCleanup(String generationId, Instant now,
                                                          Duration minimumGrace, int limit);
    boolean markRetiredVectorsDeleted(RetiredGenerationCleanup cleanup, Instant deletedAt);
    boolean completeRetiredGeneration(String generationId, Instant completedAt);
    boolean retryFailedTarget(String generationId, String revisionId, String requestedByHash,
                              String reasonCode, Instant requestedAt);
}
