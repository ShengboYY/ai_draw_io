package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanaryAutoMemoryConsolidationCandidateRetrieverTest {
    private static final AutoMemoryConsolidationQuery QUERY = new AutoMemoryConsolidationQuery(
            new TurnKey("owner-1", "conversation-1", "turn-1"),
            "chartbook-1",
            "Keep labels concise",
            16);

    @Test
    void prioritizesHydratedVectorCandidatesThenFillsFromSqlWithinTheGlobalLimit() {
        List<AutoMemoryExtractionCandidate> vectorCandidates = candidates("vector", 16);
        List<AutoMemoryExtractionCandidate> sqlCandidates = new ArrayList<>();
        sqlCandidates.add(vectorCandidates.get(0));
        sqlCandidates.addAll(candidates("sql", 19));
        FakeVectors vectors = new FakeVectors();
        vectors.hits = ids(16);
        CanaryAutoMemoryConsolidationCandidateRetriever retriever =
                new CanaryAutoMemoryConsolidationCandidateRetriever(
                        query -> sqlCandidates,
                        vectors,
                        (query, vectorIds) -> vectorCandidates);

        List<AutoMemoryExtractionCandidate> result = retriever.retrieve(QUERY);

        assertEquals(32, result.size());
        assertEquals(vectorCandidates, result.subList(0, 16));
        assertEquals(candidates("sql", 16), result.subList(16, 32));
        assertEquals(16, vectors.topK);
    }

    @Test
    void vectorSearchFailureReturnsTheOriginalSqlResult() {
        List<AutoMemoryExtractionCandidate> sqlCandidates = candidates("sql", 2);
        FakeVectors vectors = new FakeVectors();
        vectors.failure = new IllegalStateException("provider unavailable");
        CanaryAutoMemoryConsolidationCandidateRetriever retriever =
                new CanaryAutoMemoryConsolidationCandidateRetriever(
                        query -> sqlCandidates,
                        vectors,
                        (query, vectorIds) -> {
                            throw new AssertionError("hydration must not run after search failure");
                        });

        assertSame(sqlCandidates, retriever.retrieve(QUERY));
    }

    @Test
    void hydrationFailureReturnsTheOriginalSqlResult() {
        List<AutoMemoryExtractionCandidate> sqlCandidates = candidates("sql", 2);
        FakeVectors vectors = new FakeVectors();
        vectors.hits = ids(2);
        CanaryAutoMemoryConsolidationCandidateRetriever retriever =
                new CanaryAutoMemoryConsolidationCandidateRetriever(
                        query -> sqlCandidates,
                        vectors,
                        (query, vectorIds) -> {
                            throw new IllegalStateException("database unavailable");
                        });

        assertSame(sqlCandidates, retriever.retrieve(QUERY));
    }

    @Test
    void sqlFailureRemainsVisibleAndSkipsTheCanaryProvider() {
        FakeVectors vectors = new FakeVectors();
        CanaryAutoMemoryConsolidationCandidateRetriever retriever =
                new CanaryAutoMemoryConsolidationCandidateRetriever(
                        query -> {
                            throw new IllegalStateException("database unavailable");
                        },
                        vectors,
                        (query, vectorIds) -> List.of());

        assertThrows(IllegalStateException.class, () -> retriever.retrieve(QUERY));
        assertEquals(0, vectors.searchCount);
    }

    private static List<AutoMemoryExtractionCandidate> candidates(String prefix, int count) {
        List<AutoMemoryExtractionCandidate> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(new AutoMemoryExtractionCandidate(
                    MemoryScopeType.USER,
                    AutoMemoryType.PREFERENCE,
                    prefix + "-" + index,
                    prefix + " " + index,
                    "Prefer " + prefix + " " + index,
                    AutoMemoryStatus.ACTIVE));
        }
        return List.copyOf(candidates);
    }

    private static List<String> ids(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add("opaque-vector-" + index);
        }
        return List.copyOf(ids);
    }

    private static final class FakeVectors implements AutoMemoryVectorStorePort {
        private List<String> hits = List.of();
        private RuntimeException failure;
        private int topK;
        private int searchCount;

        @Override
        public List<float[]> embedPassages(List<String> texts) {
            return List.of();
        }

        @Override
        public void upsert(List<AutoMemoryVector> vectors) {
        }

        @Override
        public Set<String> existingVectorIds(List<String> vectorIds) {
            return Set.of();
        }

        @Override
        public void delete(List<String> vectorIds) {
        }

        @Override
        public List<String> search(AutoMemoryConsolidationQuery query, int topK) {
            this.searchCount++;
            this.topK = topK;
            if (failure != null) {
                throw failure;
            }
            return hits;
        }
    }
}
