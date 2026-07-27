package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.projection.LexicalProjection;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchDenseUnionLexicalStabilizerTest {

    @Test
    void shouldLexicallyRerankOnlyChunksReturnedByTheDenseUnion() {
        List<String> stabilized = ResearchDenseUnionLexicalStabilizer.stabilize(
                List.of("vector-a", "vector-shared"),
                List.of("vector-c", "vector-shared"),
                Map.of(
                        "vector-a", "lexical-target",
                        "vector-shared", "shared",
                        "vector-c", "rewritten-only"),
                "needle",
                List.of(
                        projection("outside-union", "needle"),
                        projection("lexical-target", "needle"),
                        projection("shared", "shared")),
                3);

        assertEquals(List.of("vector-a", "vector-shared", "vector-c"), stabilized);
    }

    @Test
    void shouldPreserveTheCompleteDenseUnionBeforeSelectingTop40() {
        List<String> vectors = IntStream.range(0, 45)
                .mapToObj(index -> "vector-" + index).toList();
        Map<String, String> chunks = IntStream.range(0, 45).boxed().collect(
                java.util.stream.Collectors.toMap(
                        index -> "vector-" + index, index -> "chunk-" + index));

        List<String> stabilized = ResearchDenseUnionLexicalStabilizer.stabilize(
                vectors, List.of(), chunks, "needle",
                List.of(projection("chunk-44", "needle")), 40);

        assertEquals("vector-44", stabilized.get(0));
        assertEquals(40, stabilized.size());
    }

    private LexicalProjection projection(String chunkId, String text) {
        return new LexicalProjection(chunkId, text, null, List.of());
    }
}
