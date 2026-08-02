package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryVectorProjectionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void projectsCurrentAndChallengerThenRemovesOnlyStaleVectors() {
        AutoMemory memory = memory();
        AutoMemoryVectorDocument current = AutoMemoryVectorDocument.current(memory, 4);
        AutoMemoryVectorDocument challenger = AutoMemoryVectorDocument.challenger(
                memory.memoryId(), memory.scope(), memory.title(),
                "Prefer detailed labels", 4);
        FakeWork work = new FakeWork(lease(
                List.of(current, challenger), Set.of(current.vectorId(), "stale-vector")));
        FakeVectors vectors = new FakeVectors();
        vectors.visible = Set.of(current.vectorId(), challenger.vectorId());

        assertTrue(worker(work, vectors).runOnce("worker-1"));

        assertEquals(List.of(
                "Node labels\nPrefer concise labels",
                "Node labels\nPrefer detailed labels"), vectors.embeddedTexts);
        assertEquals(List.of(current, challenger), vectors.upserted.stream()
                .map(AutoMemoryVector::document)
                .toList());
        assertEquals(List.of("stale-vector"), vectors.deleted);
        assertTrue(work.completed);
        assertFalse(work.retried);
    }

    @Test
    void deletionSnapshotRemovesPublishedVectorsWithoutEmbedding() {
        FakeWork work = new FakeWork(lease(
                List.of(), Set.of("old-current", "old-challenger")));
        FakeVectors vectors = new FakeVectors();

        assertTrue(worker(work, vectors).runOnce("worker-1"));

        assertTrue(vectors.embeddedTexts.isEmpty());
        assertTrue(vectors.upserted.isEmpty());
        assertEquals(List.of("old-challenger", "old-current"), vectors.deleted);
        assertTrue(work.completed);
    }

    @Test
    void visibilityGapUsesProviderDelayAndKeepsManifestUncommitted() {
        AutoMemoryVectorDocument current = AutoMemoryVectorDocument.current(memory(), 4);
        FakeWork work = new FakeWork(lease(List.of(current), Set.of()));
        FakeVectors vectors = new FakeVectors();

        assertTrue(worker(work, vectors).runOnce("worker-1"));

        assertFalse(work.completed);
        assertTrue(work.retried);
        assertEquals(NOW.plusSeconds(10), work.retryAt);
        assertNotNull(work.errorCode);
        assertTrue(vectors.deleted.isEmpty());
    }

    @Test
    void staleCompletionFenceDoesNotTurnNewerWorkIntoFailure() {
        AutoMemoryVectorDocument current = AutoMemoryVectorDocument.current(memory(), 4);
        FakeWork work = new FakeWork(lease(List.of(current), Set.of()));
        work.completeResult = false;
        FakeVectors vectors = new FakeVectors();
        vectors.visible = Set.of(current.vectorId());

        assertTrue(worker(work, vectors).runOnce("worker-1"));

        assertTrue(work.completeAttempted);
        assertFalse(work.completed);
        assertFalse(work.retried);
        assertEquals(List.of("memory-1"), work.enqueued);
    }

    @Test
    void failedWriteFromExpiredLeaseAlsoSchedulesReconciliation() {
        AutoMemoryVectorDocument current = AutoMemoryVectorDocument.current(memory(), 4);
        FakeWork work = new FakeWork(lease(List.of(current), Set.of()));
        work.retryResult = false;
        FakeVectors vectors = new FakeVectors();

        assertTrue(worker(work, vectors).runOnce("worker-1"));

        assertTrue(work.retried);
        assertEquals(List.of("memory-1"), work.enqueued);
    }

    private static AutoMemoryVectorProjectionWorker worker(
            FakeWork work,
            FakeVectors vectors
    ) {
        return new AutoMemoryVectorProjectionWorker(
                work, vectors, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AutoMemoryVectorProjectionLease lease(
            List<AutoMemoryVectorDocument> documents,
            Set<String> projected
    ) {
        return new AutoMemoryVectorProjectionLease(
                "memory-1", "worker-1", 2, 4, 1, documents, projected);
    }

    private static AutoMemory memory() {
        return new AutoMemory(
                "memory-1",
                AutoMemoryScope.user("owner-1"),
                AutoMemoryType.PREFERENCE,
                "node-label-density",
                "Node labels",
                "Prefer concise labels",
                AutoMemoryStatus.ACTIVE,
                0.9d,
                2,
                false,
                3,
                NOW,
                NOW);
    }

    private static final class FakeWork implements AutoMemoryVectorProjectionWorkPort {
        private AutoMemoryVectorProjectionLease lease;
        private boolean completeAttempted;
        private boolean completeResult = true;
        private boolean completed;
        private boolean retried;
        private boolean retryResult = true;
        private Instant retryAt;
        private String errorCode;
        private final List<String> enqueued = new ArrayList<>();

        private FakeWork(AutoMemoryVectorProjectionLease lease) {
            this.lease = lease;
        }

        @Override
        public void enqueue(String memoryId) {
            enqueued.add(memoryId);
        }

        @Override
        public Optional<AutoMemoryVectorProjectionLease> claim(
                String workerId,
                Instant now,
                Duration leaseDuration
        ) {
            AutoMemoryVectorProjectionLease claimed = lease;
            lease = null;
            return Optional.ofNullable(claimed);
        }

        @Override
        public boolean complete(AutoMemoryVectorProjectionLease lease, Instant now) {
            completeAttempted = true;
            completed = completeResult;
            return completeResult;
        }

        @Override
        public boolean retry(
                AutoMemoryVectorProjectionLease lease,
                String errorCode,
                Instant availableAt,
                Instant now
        ) {
            retried = true;
            retryAt = availableAt;
            this.errorCode = errorCode;
            return retryResult;
        }
    }

    private static final class FakeVectors implements AutoMemoryVectorStorePort {
        private final List<String> embeddedTexts = new ArrayList<>();
        private final List<AutoMemoryVector> upserted = new ArrayList<>();
        private List<String> deleted = List.of();
        private Set<String> visible = Set.of();

        @Override
        public List<float[]> embedPassages(List<String> texts) {
            embeddedTexts.addAll(texts);
            return texts.stream().map(ignored -> new float[]{1F, 2F}).toList();
        }

        @Override
        public void upsert(List<AutoMemoryVector> vectors) {
            upserted.addAll(vectors);
        }

        @Override
        public Set<String> existingVectorIds(List<String> vectorIds) {
            return visible;
        }

        @Override
        public void delete(List<String> vectorIds) {
            deleted = List.copyOf(vectorIds);
        }

        @Override
        public List<String> search(AutoMemoryConsolidationQuery query, int topK) {
            return List.of();
        }
    }
}
