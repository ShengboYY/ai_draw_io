package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                List.of("lexical-target", "outside-union", "shared"),
                3);

        assertEquals(List.of("vector-a", "vector-shared", "vector-c"), stabilized);
    }
}
