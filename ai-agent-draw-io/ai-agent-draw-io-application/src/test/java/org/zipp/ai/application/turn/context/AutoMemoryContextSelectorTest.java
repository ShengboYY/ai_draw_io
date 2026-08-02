package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchPort;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryContextSelectorTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void semanticOrderIsPinnedWhileChartbookOverridesTheSameDecisionKey() {
        AutoMemory chartbook = memory(
                "memory-chartbook", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "labels", "Use project labels", 2);
        AutoMemory globalSameKey = memory(
                "memory-global-labels", AutoMemoryScope.user("owner-1"),
                "labels", "Use global labels", 3);
        AutoMemory globalTheme = memory(
                "memory-global-theme", AutoMemoryScope.user("owner-1"),
                "theme", "Use a blue theme", 4);
        FakeAuthority authority = new FakeAuthority(List.of(
                chartbook, globalSameKey, globalTheme));
        FakeVectors vectors = new FakeVectors(List.of(
                vectorId(globalTheme), vectorId(chartbook), vectorId(globalSameKey)));
        AutoMemoryContextSelector selector = selector(authority, vectors, 4, 6_000);

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-global-theme", "memory-chartbook"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
        assertEquals(List.of(AutoMemoryContext.Scope.USER, AutoMemoryContext.Scope.CHARTBOOK),
                selected.context().entries().stream().map(AutoMemoryContext.Entry::scope).toList());
        assertFalse(selected.context().prioritizedEntries().stream()
                .anyMatch(value -> value.contains("Use global labels")));
        assertEquals(
                java.util.Set.of(AutoMemoryVectorDocument.CandidateKind.CURRENT),
                vectors.query.kinds());
        assertEquals(
                java.util.Set.of(AutoMemoryVectorDocument.CandidateState.ACTIVE),
                vectors.query.states());
    }

    @Test
    void vectorFailureReturnsTheStableSqlBaseline() {
        AutoMemory chartbook = memory(
                "memory-chartbook", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "layout", "Use vertical layout", 2);
        AutoMemory user = memory(
                "memory-user", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 3);
        FakeAuthority authority = new FakeAuthority(List.of(chartbook, user));
        FakeVectors vectors = new FakeVectors(new IllegalStateException("vector unavailable"));

        AutoMemoryContextSelection selected = selector(authority, vectors, 4, 6_000)
                .select(query());

        assertEquals(List.of("memory-chartbook", "memory-user"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void successfulSemanticRecallFiltersWeakHitsWithoutSqlPadding() {
        AutoMemory relevant = memory(
                "memory-relevant", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 2);
        AutoMemory weak = memory(
                "memory-weak", AutoMemoryScope.user("owner-1"),
                "theme", "Use a blue theme", 2);
        FakeAuthority authority = new FakeAuthority(List.of(relevant, weak));
        FakeVectors vectors = FakeVectors.scored(List.of(
                new AutoMemoryVectorSearchHit(vectorId(relevant), 0.82d),
                new AutoMemoryVectorSearchHit(vectorId(weak), 0.41d)));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                new AutoMemoryContextSelector.SemanticPolicy(0.70d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-relevant"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void successfulSemanticRecallMaySelectNoMemory() {
        AutoMemory baseline = memory(
                "memory-baseline", AutoMemoryScope.user("owner-1"),
                "theme", "Use a blue theme", 2);
        FakeAuthority authority = new FakeAuthority(List.of(baseline));

        AutoMemoryContextSelection selected = selector(
                authority, new FakeVectors(List.of()), 4, 6_000).select(query());

        assertTrue(selected.references().isEmpty());
    }

    @Test
    void totalCharacterBudgetSkipsEntriesThatDoNotFit() {
        AutoMemory first = memory(
                "memory-first", AutoMemoryScope.user("owner-1"),
                "first", "A".repeat(210), 1);
        AutoMemory second = memory(
                "memory-second", AutoMemoryScope.user("owner-1"),
                "second", "B".repeat(210), 1);
        FakeAuthority authority = new FakeAuthority(List.of(first, second));

        AutoMemoryContextSelection selected = selector(authority, null, 4, 256).select(query());

        assertEquals(List.of("memory-first"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void materializationRejectsAChangedPinnedVersion() {
        AutoMemory original = memory(
                "memory-user", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 3);
        FakeAuthority authority = new FakeAuthority(List.of(original));
        AutoMemoryContextSelector selector = selector(authority, null, 4, 6_000);
        AutoMemoryContextSelection selected = selector.select(query());
        authority.replace(memory(
                "memory-user", AutoMemoryScope.user("owner-1"),
                "labels", "Use very concise labels", 4));

        Optional<AutoMemoryContextSelection> materialized = selector.materialize(
                query(), selected.references());

        assertTrue(materialized.isEmpty());
    }

    private static AutoMemoryContextSelector selector(
            FakeAuthority authority,
            AutoMemoryVectorSearchPort vectors,
            int maxEntries,
            int maxCharacters
    ) {
        AutoMemoryContextSelector.Budget budget =
                new AutoMemoryContextSelector.Budget(maxEntries, maxCharacters);
        return vectors == null
                ? new AutoMemoryContextSelector(authority, authority, budget)
                : new AutoMemoryContextSelector(
                        authority,
                        authority,
                        vectors,
                        new AutoMemoryContextSelector.SemanticPolicy(0.0d),
                        budget);
    }

    private static AutoMemoryContextQuery query() {
        return new AutoMemoryContextQuery(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "book-1",
                "Add a concise approval node");
    }

    private static AutoMemory memory(
            String id,
            AutoMemoryScope scope,
            String key,
            String text,
            long version
    ) {
        return new AutoMemory(
                id,
                scope,
                AutoMemoryType.PREFERENCE,
                key,
                key,
                text,
                AutoMemoryStatus.ACTIVE,
                0.9d,
                2,
                true,
                version,
                NOW,
                NOW);
    }

    private static String vectorId(AutoMemory memory) {
        return AutoMemoryVectorDocument.current(memory, 1).vectorId();
    }

    private static final class FakeAuthority
            implements AutoMemoryQueryPort, AutoMemoryContextHydrationPort {
        private final Map<String, AutoMemory> memories = new HashMap<>();
        private final List<String> order = new ArrayList<>();

        private FakeAuthority(List<AutoMemory> initial) {
            initial.forEach(memory -> {
                memories.put(memory.memoryId(), memory);
                order.add(memory.memoryId());
            });
        }

        private void replace(AutoMemory memory) {
            memories.put(memory.memoryId(), memory);
        }

        @Override
        public List<AutoMemory> recallActive(AutoMemoryScope scope, int limit) {
            return order.stream()
                    .map(memories::get)
                    .filter(memory -> memory.scope().equals(scope))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AutoMemory> findConsolidationCandidates(AutoMemoryScope scope, int limit) {
            return List.of();
        }

        @Override
        public List<AutoMemory> hydrateActiveVectorMatches(
                AutoMemoryContextQuery query,
                List<String> rankedVectorIds
        ) {
            List<AutoMemory> result = new ArrayList<>();
            for (String vectorId : rankedVectorIds) {
                AutoMemoryVectorDocument.currentMemoryIdFromVectorId(vectorId)
                        .map(memories::get)
                        .ifPresent(result::add);
            }
            return List.copyOf(result);
        }

        @Override
        public List<AutoMemory> loadActive(
                AutoMemoryContextQuery query,
                List<String> memoryIds
        ) {
            return memoryIds.stream().map(memories::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    private static final class FakeVectors implements AutoMemoryVectorSearchPort {
        private final List<AutoMemoryVectorSearchHit> hits;
        private final RuntimeException failure;
        private AutoMemoryVectorSearchQuery query;

        private FakeVectors(List<String> hits) {
            this.hits = hits.stream()
                    .map(id -> new AutoMemoryVectorSearchHit(id, 1.0d))
                    .toList();
            this.failure = null;
        }

        private FakeVectors(
                List<AutoMemoryVectorSearchHit> hits,
                RuntimeException failure
        ) {
            this.hits = List.copyOf(hits);
            this.failure = failure;
        }

        private static FakeVectors scored(List<AutoMemoryVectorSearchHit> hits) {
            return new FakeVectors(hits, null);
        }

        private FakeVectors(RuntimeException failure) {
            this.hits = List.of();
            this.failure = failure;
        }

        @Override
        public List<AutoMemoryVectorSearchHit> search(
                AutoMemoryVectorSearchQuery query,
                int topK
        ) {
            this.query = query;
            if (failure != null) {
                throw failure;
            }
            return hits.subList(0, Math.min(topK, hits.size()));
        }
    }
}
