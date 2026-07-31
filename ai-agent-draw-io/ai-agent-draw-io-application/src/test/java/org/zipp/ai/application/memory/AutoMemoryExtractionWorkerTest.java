package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryExtractionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void inferredDraftStaysObservedAndCompletesTheLease() {
        FakeWork work = new FakeWork(lease(null));
        RecordingObservationStore store = new RecordingObservationStore();
        AutoMemoryExtractionWorker worker = worker(
                work,
                input -> List.of(new AutoMemoryExtractionDraft(
                        MemoryScopeType.USER,
                        AutoMemoryType.PREFERENCE,
                        "label-density",
                        "Label preference",
                        "Prefer concise labels",
                        0.8d)),
                store);

        assertTrue(worker.runOnce("worker-1"));

        assertTrue(work.completed);
        assertFalse(work.retried);
        assertEquals(1, store.applied.size());
        assertEquals(MemoryScopeType.USER, store.applied.get(0).scope().type());
        assertEquals(AutoMemoryStatus.OBSERVED, store.memories.get(0).status());
    }

    @Test
    void explicitDeclarationBypassesTheModelAndBecomesActive() {
        FakeWork work = new FakeWork(lease("Prefer concise labels"));
        RecordingObservationStore store = new RecordingObservationStore();
        int[] modelCalls = {0};
        AutoMemoryExtractionWorker worker = worker(work, input -> {
            modelCalls[0]++;
            return List.of();
        }, store);

        assertTrue(worker.runOnce("worker-1"));

        assertEquals(0, modelCalls[0]);
        assertTrue(work.completed);
        assertEquals(AutoMemoryStatus.ACTIVE, store.memories.get(0).status());
        assertTrue(store.applied.get(0).explicit());
        assertEquals(MemoryScopeType.CHARTBOOK, store.applied.get(0).scope().type());
    }

    @Test
    void explicitAllChartbooksInstructionBecomesActiveUserMemoryWithoutAChartbook() {
        AutoMemoryExtractionLease lease = new AutoMemoryExtractionLease(
                "work-1",
                "worker-1",
                2,
                1,
                TURN,
                "diagram-1",
                null,
                "请记住这个决定：所有画册都使用简洁标签",
                null);
        FakeWork work = new FakeWork(lease);
        RecordingObservationStore store = new RecordingObservationStore();
        int[] modelCalls = {0};
        AutoMemoryExtractionWorker worker = worker(work, input -> {
            modelCalls[0]++;
            return List.of();
        }, store);

        assertTrue(worker.runOnce("worker-1"));

        assertEquals(0, modelCalls[0]);
        assertTrue(work.completed);
        assertEquals(1, store.applied.size());
        assertEquals(MemoryScopeType.USER, store.applied.get(0).scope().type());
        assertEquals(AutoMemoryStatus.ACTIVE, store.memories.get(0).status());
        assertTrue(store.applied.get(0).explicit());
    }

    @Test
    void extractorFailureReturnsTheDurableItemForRetry() {
        FakeWork work = new FakeWork(lease(null));
        AutoMemoryExtractionWorker worker = worker(
                work,
                input -> {
                    throw new IllegalStateException("provider unavailable");
                },
                new RecordingObservationStore());

        assertTrue(worker.runOnce("worker-1"));

        assertFalse(work.completed);
        assertTrue(work.retried);
    }

    private static AutoMemoryExtractionWorker worker(
            FakeWork work,
            AutoMemoryExtractionPort extractor,
            RecordingObservationStore store
    ) {
        AutoMemoryObservationService observations = new AutoMemoryObservationService(
                new MemoryPolicySanitizer(),
                new AutoMemoryActivationPolicy(),
                store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new AutoMemoryExtractionWorker(
                work, extractor, observations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AutoMemoryExtractionLease lease(String explicitText) {
        return new AutoMemoryExtractionLease(
                "work-1",
                "worker-1",
                2,
                1,
                TURN,
                "diagram-1",
                "chartbook-1",
                "Keep labels concise in future diagrams",
                explicitText);
    }

    private static final class FakeWork implements AutoMemoryExtractionWorkPort {
        private final AutoMemoryExtractionLease lease;
        private boolean claimed;
        private boolean completed;
        private boolean retried;

        private FakeWork(AutoMemoryExtractionLease lease) {
            this.lease = lease;
        }

        @Override
        public void enqueue(TurnKey turn, String diagramId) {
        }

        @Override
        public Optional<AutoMemoryExtractionLease> claim(
                String workerId, Instant now, Duration leaseDuration) {
            if (claimed) {
                return Optional.empty();
            }
            claimed = true;
            return Optional.of(lease);
        }

        @Override
        public boolean complete(AutoMemoryExtractionLease lease, Instant now) {
            completed = true;
            return true;
        }

        @Override
        public boolean retry(
                AutoMemoryExtractionLease lease,
                String errorCode,
                Instant availableAt,
                Instant now) {
            retried = true;
            return true;
        }
    }

    private static final class RecordingObservationStore
            implements AutoMemoryObservationStorePort {
        private final List<SanitizedAutoMemoryObservation> applied = new ArrayList<>();
        private final List<AutoMemory> memories = new ArrayList<>();

        @Override
        public AutoMemoryObservationOutcome observe(
                SanitizedAutoMemoryObservation observation,
                AutoMemoryActivationPolicy activationPolicy) {
            applied.add(observation);
            AutoMemoryStatus status = activationPolicy.initialStatus(observation.explicit());
            AutoMemory memory = new AutoMemory(
                    "memory-" + applied.size(),
                    observation.scope(),
                    observation.type(),
                    observation.semanticKey(),
                    observation.title(),
                    observation.canonicalText(),
                    status,
                    observation.confidence(),
                    1,
                    observation.explicit(),
                    1,
                    observation.observedAt(),
                    observation.observedAt());
            memories.add(memory);
            return new AutoMemoryObservationOutcome.Applied(
                    memory, true, status == AutoMemoryStatus.ACTIVE);
        }
    }
}
