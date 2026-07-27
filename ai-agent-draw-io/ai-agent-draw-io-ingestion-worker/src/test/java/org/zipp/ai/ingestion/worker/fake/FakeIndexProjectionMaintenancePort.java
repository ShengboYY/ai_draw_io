package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.IndexProjectionMaintenancePort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class FakeIndexProjectionMaintenancePort implements IndexProjectionMaintenancePort {
    public List<ProjectionBatchInventory> dueBatches = List.of();
    public final List<ProjectionBatchInventory> checkedBatches = new ArrayList<>();
    public final List<Set<String>> missingRepairs = new ArrayList<>();
    public Set<String> knownVectorIds = Set.of();
    public final List<String> orphanDeletions = new ArrayList<>();
    public final List<String> completedOrphanDeletions = new ArrayList<>();
    public String providerCursor;
    public String temporaryCleanupCursor = "";
    public TemporaryProjectionCleanup temporaryCleanup;
    public final List<String> temporaryCleanupCursors = new ArrayList<>();
    public final List<String> temporaryMarked = new ArrayList<>();
    public PendingProjectionDeletion temporaryProviderDeletionRetry;
    public final List<String> temporaryCompleted = new ArrayList<>();
    public String temporaryCompletedGeneration;
    public boolean temporaryMarkAccepted = true;
    public RetiredGenerationCleanup retiredCleanup;
    public final List<String> retiredMarked = new ArrayList<>();
    public boolean retiredCompleted;
    public VectorRepairWork repairWork;
    public boolean repairCompleted;

    @Override
    public List<ProjectionBatchInventory> findBatchesDue(String generationId, Instant dueBefore, int limit) {
        return dueBatches;
    }

    @Override
    public boolean markBatchChecked(ProjectionBatchInventory batch, Instant checkedAt) {
        checkedBatches.add(batch);
        return true;
    }

    @Override
    public boolean scheduleMissingVectorRepair(ProjectionBatchInventory batch, Set<String> missingVectorIds,
                                               Instant requestedAt) {
        missingRepairs.add(Set.copyOf(missingVectorIds));
        return true;
    }

    @Override
    public Optional<VectorRepairWork> findRepairWork(String revisionId, String workKey, WorkerFence fence) {
        return Optional.ofNullable(repairWork);
    }

    @Override
    public boolean completeRepair(VectorRepairWork work, WorkerFence fence, Instant completedAt) {
        repairCompleted = true;
        return true;
    }

    @Override
    public String findProviderCursor(String generationId, Instant initializedAt) {
        return providerCursor;
    }

    @Override
    public boolean advanceProviderCursor(String generationId, String expectedCursor, String nextCursor,
                                         Instant updatedAt) {
        if (!java.util.Objects.equals(providerCursor, expectedCursor)) return false;
        providerCursor = nextCursor;
        return true;
    }

    @Override
    public Set<String> findKnownVectorIds(List<String> providerVectorIds) {
        return knownVectorIds;
    }

    @Override
    public String recordOrphanDeletionIntent(String generationId, List<String> vectorIds, Instant requestedAt) {
        orphanDeletions.addAll(vectorIds);
        return "orphan_1";
    }

    @Override
    public boolean completeOrphanDeletion(String deletionId, Instant completedAt) {
        completedOrphanDeletions.add(deletionId);
        return true;
    }

    @Override
    public String findTemporaryCleanupCursor(String generationId, Instant initializedAt) {
        return temporaryCleanupCursor;
    }

    @Override
    public boolean advanceTemporaryCleanupCursor(String generationId, String expectedMaterialId,
                                                 String nextMaterialId, Instant updatedAt) {
        if (!java.util.Objects.equals(temporaryCleanupCursor, expectedMaterialId)) return false;
        temporaryCleanupCursor = nextMaterialId;
        return true;
    }

    @Override
    public Optional<TemporaryProjectionCleanup> findTemporaryConversationCleanup(
            String generationId, String afterMaterialId, Instant now, int limit) {
        temporaryCleanupCursors.add(afterMaterialId);
        return Optional.ofNullable(temporaryCleanup);
    }

    @Override
    public boolean claimTemporaryConversationVectorsForDeletion(
            TemporaryProjectionCleanup cleanup, Instant deletedAt) {
        if (!temporaryMarkAccepted) return false;
        temporaryMarked.addAll(cleanup.vectorIds());
        return true;
    }

    @Override
    public Optional<PendingProjectionDeletion> findTemporaryProviderDeletionRetry(int limit) {
        return Optional.ofNullable(temporaryProviderDeletionRetry);
    }

    @Override
    public boolean completeTemporaryProviderDeletion(String generationId, List<String> vectorIds,
                                                     Instant completedAt) {
        temporaryCompletedGeneration = generationId;
        temporaryCompleted.addAll(vectorIds);
        temporaryProviderDeletionRetry = null;
        return true;
    }

    @Override
    public Optional<RetiredGenerationCleanup> findRetiredCleanup(
            Instant now, Duration minimumGrace, int limit) {
        return Optional.ofNullable(retiredCleanup);
    }

    @Override
    public boolean markRetiredVectorsDeleted(RetiredGenerationCleanup cleanup, Instant deletedAt) {
        retiredMarked.addAll(cleanup.vectorIds());
        return true;
    }

    @Override
    public boolean completeRetiredGeneration(String generationId, Instant completedAt) {
        retiredCompleted = true;
        return true;
    }

    @Override
    public boolean retryFailedTarget(String generationId, String revisionId, String requestedByHash,
                                     String reasonCode, Instant requestedAt) {
        return true;
    }
}
