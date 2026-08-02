package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopedAutoMemoryConsolidationCandidateRetrieverTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void retrievesOnlyAuthorizedUserAndCurrentChartbookScopes() {
        RecordingQueryPort memories = new RecordingQueryPort(List.of(
                memory("user", AutoMemoryScope.user("owner-1")),
                memory("chartbook", AutoMemoryScope.chartbook("owner-1", "chartbook-1")),
                memory("other-chartbook", AutoMemoryScope.chartbook("owner-1", "chartbook-2")),
                memory("other-owner", AutoMemoryScope.user("owner-2"))));
        ScopedAutoMemoryConsolidationCandidateRetriever retriever =
                new ScopedAutoMemoryConsolidationCandidateRetriever(memories);

        List<AutoMemoryExtractionCandidate> actual = retriever.retrieve(
                new AutoMemoryConsolidationQuery(
                        TURN, " chartbook-1 ", "Keep labels concise", 4));

        assertEquals(List.of("user", "chartbook"), actual.stream()
                .map(AutoMemoryExtractionCandidate::semanticKey)
                .toList());
        assertEquals(List.of(
                AutoMemoryScope.user("owner-1"),
                AutoMemoryScope.chartbook("owner-1", "chartbook-1")),
                memories.requestedScopes);
    }

    @Test
    void queryWithoutChartbookCannotWidenToAChartbookScope() {
        RecordingQueryPort memories = new RecordingQueryPort(List.of(
                memory("user", AutoMemoryScope.user("owner-1")),
                memory("chartbook", AutoMemoryScope.chartbook("owner-1", "chartbook-1"))));
        ScopedAutoMemoryConsolidationCandidateRetriever retriever =
                new ScopedAutoMemoryConsolidationCandidateRetriever(memories);

        List<AutoMemoryExtractionCandidate> actual = retriever.retrieve(
                new AutoMemoryConsolidationQuery(TURN, null, "Prefer short labels", 1));

        assertEquals(List.of("user"), actual.stream()
                .map(AutoMemoryExtractionCandidate::semanticKey)
                .toList());
        assertEquals(List.of(AutoMemoryScope.user("owner-1")), memories.requestedScopes);
    }

    @Test
    void queryRejectsUnboundedOrEmptyInput() {
        assertThrows(IllegalArgumentException.class, () ->
                new AutoMemoryConsolidationQuery(TURN, null, " ", 1));
        assertThrows(IllegalArgumentException.class, () ->
                new AutoMemoryConsolidationQuery(TURN, null, "labels", 17));
    }

    private static AutoMemory memory(String key, AutoMemoryScope scope) {
        return new AutoMemory(
                "memory-" + key,
                scope,
                AutoMemoryType.PREFERENCE,
                key,
                "Memory " + key,
                "Canonical " + key,
                AutoMemoryStatus.ACTIVE,
                0.8d,
                2,
                false,
                1,
                NOW,
                NOW);
    }

    private static final class RecordingQueryPort implements AutoMemoryQueryPort {
        private final List<AutoMemory> memories;
        private final List<AutoMemoryScope> requestedScopes = new ArrayList<>();

        private RecordingQueryPort(List<AutoMemory> memories) {
            this.memories = List.copyOf(memories);
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
            requestedScopes.add(scope);
            return memories.stream()
                    .filter(memory -> memory.scope().equals(scope))
                    .limit(limit)
                    .toList();
        }
    }
}
