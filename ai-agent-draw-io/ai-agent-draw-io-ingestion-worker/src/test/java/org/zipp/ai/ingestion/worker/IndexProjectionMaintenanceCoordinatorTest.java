package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.ingestion.worker.fake.FakeIndexProjectionMaintenancePort;
import org.zipp.ai.ingestion.worker.fake.FakeRetrievalVectorIndex;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexProjectionMaintenanceCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void schedulesMissingBatchRepairAndDeletesOnlyProviderOrphans() {
        VectorGenerationProfile profile = profile();
        FakeIndexProjectionMaintenancePort maintenance = new FakeIndexProjectionMaintenancePort();
        maintenance.dueBatches = List.of(new ProjectionBatchInventory(profile.generationId(),
                "rev_1", 0, "a".repeat(64), List.of("vector_expected", "vector_missing")));
        maintenance.knownVectorIds = Set.of("vector_expected");
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(vector(profile, "vector_expected"), vector(profile, "vector_orphan")));
        IndexProjectionMaintenanceCoordinator coordinator = coordinator(maintenance, index, profile);

        coordinator.maintain();

        assertEquals(List.of(Set.of("vector_missing")), maintenance.missingRepairs);
        assertTrue(maintenance.checkedBatches.isEmpty());
        assertEquals(List.of("vector_orphan"), maintenance.orphanDeletions);
        assertEquals(List.of("orphan_1"), maintenance.completedOrphanDeletions);
        assertEquals(Set.of("vector_expected"), index.existingVectorIds(
                List.of("vector_expected", "vector_orphan")));
    }

    @Test
    void retiredCleanupWaitsForProviderAbsenceBeforeWritingTombstones() {
        VectorGenerationProfile profile = profile();
        FakeIndexProjectionMaintenancePort maintenance = new FakeIndexProjectionMaintenancePort();
        maintenance.knownVectorIds = Set.of("vector_old");
        maintenance.retiredCleanup = new RetiredGenerationCleanup(
                profile.generationId(), NOW.minusSeconds(1), List.of("vector_old"));
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(vector(profile, "vector_old")));
        IndexProjectionMaintenanceCoordinator coordinator = coordinator(maintenance, index, profile);

        coordinator.maintain();
        assertTrue(maintenance.retiredMarked.isEmpty());

        coordinator.maintain();
        assertEquals(List.of("vector_old"), maintenance.retiredMarked);

        maintenance.retiredCleanup = new RetiredGenerationCleanup(
                profile.generationId(), NOW.minusSeconds(1), List.of());
        coordinator.maintain();
        assertTrue(maintenance.retiredCompleted);
    }

    private IndexProjectionMaintenanceCoordinator coordinator(
            FakeIndexProjectionMaintenancePort maintenance, FakeRetrievalVectorIndex index,
            VectorGenerationProfile profile) {
        return new IndexProjectionMaintenanceCoordinator(maintenance, index, profile,
                Duration.ofHours(24), Duration.ofHours(24), 100,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private VectorGenerationProfile profile() {
        return new VectorGenerationProfile("index", "namespace", "model", "e".repeat(64),
                4, "cosine", "vector-v1", "tokenizer-v1");
    }

    private VectorProjection vector(VectorGenerationProfile profile, String id) {
        return new VectorProjection("chunk_" + id, profile.generationId(), id,
                new float[4], Map.of("tenant_key", "tenant"));
    }
}
