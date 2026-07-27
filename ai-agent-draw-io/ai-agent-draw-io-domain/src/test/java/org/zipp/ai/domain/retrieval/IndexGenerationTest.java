package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.model.aggregate.IndexGeneration;
import org.zipp.ai.domain.retrieval.model.valobj.IndexGenerationState;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexGenerationTest {

    @Test
    void indexGenerationAdvancesThroughShadowBeforeActivation() {
        IndexGeneration generation = IndexGeneration.building(
                "ig_2", "rag-v2", "multilingual-e5-large", 1024, "cosine", "vector-v1");

        assertThrows(IllegalStateException.class,
                () -> generation.activate(Instant.parse("2026-07-19T00:00:00Z")));
        generation.beginShadow();
        generation.activate(Instant.parse("2026-07-19T00:00:00Z"));

        assertEquals(IndexGenerationState.ACTIVE, generation.state());
    }
}
