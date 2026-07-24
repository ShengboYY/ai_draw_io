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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                "ig_retired", "retired-namespace", NOW.minusSeconds(1), List.of("vector_old"));
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(vector(profile, "vector_old")));
        AtomicReference<String> routedNamespace = new AtomicReference<>();
        IndexProjectionMaintenanceCoordinator coordinator = new IndexProjectionMaintenanceCoordinator(
                maintenance, index, namespace -> {
                    routedNamespace.set(namespace);
                    return index;
                }, profile, Duration.ofHours(24), Duration.ofHours(24), 100,
                Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.maintain();
        assertEquals("retired-namespace", routedNamespace.get());
        assertTrue(maintenance.retiredMarked.isEmpty());

        coordinator.maintain();
        assertEquals(List.of("vector_old"), maintenance.retiredMarked);

        maintenance.retiredCleanup = new RetiredGenerationCleanup(
                "ig_retired", "retired-namespace", NOW.minusSeconds(1), List.of());
        coordinator.maintain();
        assertTrue(maintenance.retiredCompleted);
    }

    @Test
    void deletesTemporaryConversationVectorsAndTombstonesAlreadyAbsentIds() {
        VectorGenerationProfile profile = profile();
        FakeIndexProjectionMaintenancePort maintenance = new FakeIndexProjectionMaintenancePort();
        maintenance.temporaryCleanup = new TemporaryProjectionCleanup(
                profile.generationId(), "material_temp",
                List.of("vector_temp", "vector_already_absent"));
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(vector(profile, "vector_temp")));

        coordinator(maintenance, index, profile).maintain();

        assertEquals(Set.of(), index.existingVectorIds(List.of("vector_temp")));
        assertEquals(List.of("vector_temp", "vector_already_absent"), maintenance.temporaryMarked);
        assertEquals(List.of("vector_temp", "vector_already_absent"), maintenance.temporaryCompleted);
    }

    @Test
    void leavesProviderVectorsUntouchedWhenTemporaryCleanupLosesItsFence() {
        VectorGenerationProfile profile = profile();
        FakeIndexProjectionMaintenancePort maintenance = new FakeIndexProjectionMaintenancePort();
        maintenance.temporaryCleanup = new TemporaryProjectionCleanup(
                profile.generationId(), "material_promoted", List.of("vector_live"));
        maintenance.knownVectorIds = Set.of("vector_live");
        maintenance.temporaryMarkAccepted = false;
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(vector(profile, "vector_live")));

        coordinator(maintenance, index, profile).maintain();

        assertEquals(Set.of("vector_live"), index.existingVectorIds(List.of("vector_live")));
    }

    @Test
    void rotatesTemporaryCleanupMaterialCursorBetweenMaintenanceRuns() {
        VectorGenerationProfile profile = profile();
        FakeIndexProjectionMaintenancePort maintenance = new FakeIndexProjectionMaintenancePort();
        maintenance.temporaryCleanup = new TemporaryProjectionCleanup(
                profile.generationId(), "material_a", List.of("vector_a"));
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        coordinator(maintenance, index, profile).maintain();
        maintenance.temporaryCleanup = new TemporaryProjectionCleanup(
                profile.generationId(), "material_b", List.of("vector_b"));
        // A recreated coordinator must continue from the durable port cursor.
        coordinator(maintenance, index, profile).maintain();

        assertTrue(maintenance.temporaryCleanupCursors.contains("material_a"));
        assertEquals("material_b", maintenance.temporaryCleanupCursor);
    }

    @Test
    void retriesExactClaimedVectorsAfterAProviderDeleteFailure() {
        VectorGenerationProfile profile = profile();
        FakeIndexProjectionMaintenancePort maintenance = new FakeIndexProjectionMaintenancePort();
        maintenance.temporaryCleanup = new TemporaryProjectionCleanup(
                profile.generationId(), "material_temp", List.of("vector_temp"));
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(vector(profile, "vector_temp")));
        index.failNextDelete();

        assertThrows(IllegalStateException.class,
                () -> coordinator(maintenance, index, profile).maintain());
        assertEquals(List.of(), maintenance.temporaryCompleted);

        maintenance.temporaryCleanup = null;
        maintenance.temporaryProviderDeletionRetry = new PendingProjectionDeletion(
                "ig_previous", "previous-namespace", List.of("vector_temp"));
        coordinator(maintenance, index, profile).maintain();

        assertEquals(Set.of(), index.existingVectorIds(List.of("vector_temp")));
        assertEquals("ig_previous", maintenance.temporaryCompletedGeneration);
        assertEquals(List.of("vector_temp"), maintenance.temporaryCompleted);
    }

    private IndexProjectionMaintenanceCoordinator coordinator(
            FakeIndexProjectionMaintenancePort maintenance, FakeRetrievalVectorIndex index,
            VectorGenerationProfile profile) {
        return new IndexProjectionMaintenanceCoordinator(maintenance, index,
                ignored -> index, profile,
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
