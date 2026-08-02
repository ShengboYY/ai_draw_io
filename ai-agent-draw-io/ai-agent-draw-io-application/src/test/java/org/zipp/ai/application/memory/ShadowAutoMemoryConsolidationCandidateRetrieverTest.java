package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowAutoMemoryConsolidationCandidateRetrieverTest {
    private static final AutoMemoryConsolidationQuery QUERY = new AutoMemoryConsolidationQuery(
            new TurnKey("owner-1", "conversation-1", "turn-1"),
            "chartbook-1",
            "Keep labels concise",
            16);
    private static final AutoMemoryExtractionCandidate SQL_CANDIDATE =
            new AutoMemoryExtractionCandidate(
                    MemoryScopeType.USER,
                    AutoMemoryType.PREFERENCE,
                    "node-label-density",
                    "Node labels",
                    "Prefer concise labels",
                    AutoMemoryStatus.ACTIVE);

    @Test
    void recordsOpaqueHitsButReturnsSqlCandidates() {
        List<AutoMemoryVectorShadowTelemetry.Sample> samples = new ArrayList<>();
        FakeVectors vectors = new FakeVectors();
        vectors.hits = List.of("opaque-vector-1");
        ShadowAutoMemoryConsolidationCandidateRetriever retriever =
                new ShadowAutoMemoryConsolidationCandidateRetriever(
                        query -> List.of(SQL_CANDIDATE), vectors, samples::add);

        assertEquals(List.of(SQL_CANDIDATE), retriever.retrieve(QUERY));

        assertEquals(1, samples.size());
        assertTrue(samples.get(0).succeeded());
        assertEquals(1, samples.get(0).vectorHitCount());
        assertEquals(32, vectors.topK);
    }

    @Test
    void vectorFailureCannotRemoveOrReplaceSqlCandidates() {
        List<AutoMemoryVectorShadowTelemetry.Sample> samples = new ArrayList<>();
        FakeVectors vectors = new FakeVectors();
        vectors.failure = new IllegalStateException("provider unavailable");
        ShadowAutoMemoryConsolidationCandidateRetriever retriever =
                new ShadowAutoMemoryConsolidationCandidateRetriever(
                        query -> List.of(SQL_CANDIDATE), vectors, samples::add);

        assertEquals(List.of(SQL_CANDIDATE), retriever.retrieve(QUERY));

        assertFalse(samples.get(0).succeeded());
        assertEquals(0, samples.get(0).vectorHitCount());
    }

    private static final class FakeVectors implements AutoMemoryVectorStorePort {
        private List<String> hits = List.of();
        private RuntimeException failure;
        private int topK;

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
            this.topK = topK;
            if (failure != null) {
                throw failure;
            }
            return hits;
        }
    }
}
