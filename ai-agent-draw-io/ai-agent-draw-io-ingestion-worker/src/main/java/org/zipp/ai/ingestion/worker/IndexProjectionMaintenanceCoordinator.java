package org.zipp.ai.ingestion.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.retrieval.model.valobj.ProjectionBatchInventory;
import org.zipp.ai.domain.retrieval.model.valobj.RetiredGenerationCleanup;
import org.zipp.ai.domain.retrieval.model.valobj.VectorIdPage;
import org.zipp.ai.domain.retrieval.port.IndexProjectionMaintenancePort;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded scheduled maintenance for provider drift without weakening MySQL authority. */
public final class IndexProjectionMaintenanceCoordinator {
    private final IndexProjectionMaintenancePort maintenance;
    private final RetrievalVectorIndex vectorIndex;
    private final VectorGenerationProfile profile;
    private final Duration reconciliationInterval;
    private final Duration retiredCleanupGrace;
    private final int batchLimit;
    private final Clock clock;

    public IndexProjectionMaintenanceCoordinator(IndexProjectionMaintenancePort maintenance,
                                                 RetrievalVectorIndex vectorIndex,
                                                 VectorGenerationProfile profile,
                                                 Duration reconciliationInterval,
                                                 Duration retiredCleanupGrace,
                                                 int batchLimit, Clock clock) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.vectorIndex = Objects.requireNonNull(vectorIndex, "vectorIndex");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.reconciliationInterval = positive(reconciliationInterval, "reconciliationInterval");
        this.retiredCleanupGrace = positive(retiredCleanupGrace, "retiredCleanupGrace");
        if (batchLimit < 1 || batchLimit > 100) {
            throw new IllegalArgumentException("batchLimit must be between 1 and 100");
        }
        this.batchLimit = batchLimit;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${worker.projection-maintenance-delay-ms:86400000}")
    public void maintain() {
        Instant now = clock.instant();
        reconcileExpectedBatches(now);
        reconcileProviderOrphans(now);
        cleanRetiredGeneration(now);
    }

    /** Controlled operator entry point; the hash identifies the actor without storing identity. */
    public boolean retryFailedTarget(String revisionId, String requestedByHash, String reasonCode) {
        return maintenance.retryFailedTarget(profile.generationId(), revisionId,
                requestedByHash, reasonCode, clock.instant());
    }

    private void reconcileExpectedBatches(Instant now) {
        for (ProjectionBatchInventory batch : maintenance.findBatchesDue(
                profile.generationId(), now.minus(reconciliationInterval), batchLimit)) {
            Set<String> existing = vectorIndex.existingVectorIds(batch.vectorIds());
            Set<String> missing = new HashSet<>(batch.vectorIds());
            missing.removeAll(existing);
            if (missing.isEmpty()) {
                maintenance.markBatchChecked(batch, now);
            } else {
                maintenance.scheduleMissingVectorRepair(batch, missing, now);
            }
        }
    }

    private void reconcileProviderOrphans(Instant now) {
        String generationId = profile.generationId();
        String providerCursor = maintenance.findProviderCursor(generationId, now);
        VectorIdPage page = vectorIndex.listVectorIds(providerCursor, 100);
        Set<String> known = maintenance.findKnownVectorIds(page.vectorIds());
        List<String> orphans = page.vectorIds().stream().filter(id -> !known.contains(id)).toList();
        if (!orphans.isEmpty()) {
            // Provider delete is exact-ID only; metadata filters never define deletion authority.
            String deletionId = maintenance.recordOrphanDeletionIntent(generationId, orphans, now);
            vectorIndex.delete(orphans);
            // Completion is idempotent across replicas; the durable intent already proves the attempt.
            maintenance.completeOrphanDeletion(deletionId, now);
        }
        maintenance.advanceProviderCursor(generationId, providerCursor, page.nextToken(), now);
    }

    private void cleanRetiredGeneration(Instant now) {
        RetiredGenerationCleanup cleanup = maintenance.findRetiredCleanup(
                profile.generationId(), now, retiredCleanupGrace, batchLimit).orElse(null);
        if (cleanup == null) return;
        if (cleanup.vectorIds().isEmpty()) {
            maintenance.completeRetiredGeneration(cleanup.generationId(), now);
            return;
        }
        Set<String> existing = vectorIndex.existingVectorIds(cleanup.vectorIds());
        if (!existing.isEmpty()) {
            vectorIndex.delete(existing.stream().sorted().toList());
        }
        List<String> alreadyAbsent = cleanup.vectorIds().stream()
                .filter(id -> !existing.contains(id)).toList();
        if (!alreadyAbsent.isEmpty()) {
            maintenance.markRetiredVectorsDeleted(
                    new RetiredGenerationCleanup(cleanup.generationId(), cleanup.eligibleAt(), alreadyAbsent), now);
        }
    }

    private static Duration positive(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }
}
