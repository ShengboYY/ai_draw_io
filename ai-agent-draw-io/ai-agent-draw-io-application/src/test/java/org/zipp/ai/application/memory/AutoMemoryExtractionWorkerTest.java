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
    void clearNonMemoryTurnCompletesWithoutCallingTheModel() {
        FakeWork work = new FakeWork(lease(null, "你好"));
        RecordingObservationStore store = new RecordingObservationStore();
        int[] modelCalls = {0};
        AutoMemoryExtractionWorker worker = worker(work, input -> {
            modelCalls[0]++;
            return List.of();
        }, store);

        assertTrue(worker.runOnce("worker-1"));

        assertEquals(0, modelCalls[0]);
        assertTrue(work.completed);
        assertFalse(work.retried);
        assertTrue(store.applied.isEmpty());
    }

    @Test
    void reusedCandidateKeepsCanonicalFieldsForEvidenceConsolidation() {
        AutoMemory existing = memory(
                AutoMemoryScope.user("owner-1"),
                AutoMemoryType.PREFERENCE,
                "node-colors-layout",
                "Node colors and layout",
                "Prefer dark blue main nodes and left-to-right layout",
                AutoMemoryStatus.OBSERVED);
        FakeWork work = new FakeWork(lease(
                null,
                "Across all projects, keep dark blue main nodes and arrange them left to right"));
        RecordingObservationStore store = new RecordingObservationStore();
        AutoMemoryExtractionWorker worker = worker(
                work,
                input -> {
                    assertEquals(1, input.existingCandidates().size());
                    return List.of(new AutoMemoryExtractionDraft(
                            MemoryScopeType.USER,
                            AutoMemoryType.PROJECT,
                            "node-colors-layout",
                            "Different generated title",
                            "Prefer dark blue main nodes and left-to-right layout",
                            0.9d));
                },
                store,
                List.of(existing));

        assertTrue(worker.runOnce("worker-1"));

        SanitizedAutoMemoryObservation applied = store.applied.get(0);
        assertEquals(existing.type(), applied.type());
        assertEquals(existing.semanticKey(), applied.semanticKey());
        assertEquals(existing.title(), applied.title());
        assertEquals(existing.canonicalText(), applied.canonicalText());
    }

    @Test
    void sameDecisionDimensionPreservesAChallengerValue() {
        AutoMemory existing = memory(
                AutoMemoryScope.user("owner-1"),
                AutoMemoryType.PREFERENCE,
                "node-label-density",
                "Node label density",
                "Prefer concise node labels",
                AutoMemoryStatus.ACTIVE);
        FakeWork work = new FakeWork(lease(
                null,
                "Across all projects, use detailed node labels from now on"));
        RecordingObservationStore store = new RecordingObservationStore();
        AutoMemoryExtractionWorker worker = worker(
                work,
                input -> List.of(new AutoMemoryExtractionDraft(
                        MemoryScopeType.USER,
                        AutoMemoryType.PROJECT,
                        "node-label-density",
                        "Generated title",
                        "Prefer detailed node labels",
                        0.9d)),
                store,
                List.of(existing));

        assertTrue(worker.runOnce("worker-1"));

        SanitizedAutoMemoryObservation applied = store.applied.get(0);
        assertEquals(existing.type(), applied.type());
        assertEquals(existing.semanticKey(), applied.semanticKey());
        assertEquals(existing.title(), applied.title());
        assertEquals("Prefer detailed node labels", applied.canonicalText());
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
        return worker(work, extractor, store, List.of());
    }

    private static AutoMemoryExtractionWorker worker(
            FakeWork work,
            AutoMemoryExtractionPort extractor,
            RecordingObservationStore store,
            List<AutoMemory> candidates
    ) {
        AutoMemoryObservationService observations = new AutoMemoryObservationService(
                new MemoryPolicySanitizer(),
                new AutoMemoryActivationPolicy(),
                store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new AutoMemoryExtractionWorker(
                work,
                extractor,
                new ScopedAutoMemoryConsolidationCandidateRetriever(
                        new FakeMemoryQuery(candidates)),
                observations,
                new AutoMemoryExtractionEligibilityPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AutoMemoryExtractionLease lease(String explicitText) {
        return lease(explicitText, "Keep labels concise in future diagrams");
    }

    private static AutoMemoryExtractionLease lease(String explicitText, String userContent) {
        return new AutoMemoryExtractionLease(
                "work-1",
                "worker-1",
                2,
                1,
                TURN,
                "diagram-1",
                "chartbook-1",
                userContent,
                explicitText);
    }

    private static AutoMemory memory(
            AutoMemoryScope scope,
            AutoMemoryType type,
            String semanticKey,
            String title,
            String canonicalText,
            AutoMemoryStatus status
    ) {
        return new AutoMemory(
                "memory-1",
                scope,
                type,
                semanticKey,
                title,
                canonicalText,
                status,
                0.8d,
                1,
                false,
                1,
                NOW,
                NOW);
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

    private static final class FakeMemoryQuery implements AutoMemoryQueryPort {
        private final List<AutoMemory> candidates;

        private FakeMemoryQuery(List<AutoMemory> candidates) {
            this.candidates = List.copyOf(candidates);
        }

        @Override
        public List<AutoMemory> recallActive(AutoMemoryScope scope, int limit) {
            return List.of();
        }

        @Override
        public List<AutoMemory> findConsolidationCandidates(
                AutoMemoryScope scope,
                int limit
        ) {
            return candidates.stream()
                    .filter(memory -> memory.scope().equals(scope))
                    .limit(limit)
                    .toList();
        }
    }
}
