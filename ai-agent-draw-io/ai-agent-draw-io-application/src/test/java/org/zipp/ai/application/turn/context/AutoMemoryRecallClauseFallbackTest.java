package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryRecallClauseFallbackTest {
    private final AutoMemoryRecallClauseFallback fallback =
            new AutoMemoryRecallClauseFallback();

    @Test
    void splitsOnlyExplicitBoundariesIntoBoundedFacets() {
        assertEquals(
                List.of("Sources stay left", "sinks stay right"),
                fallback.facets("Sources stay left; sinks stay right"));
        assertEquals(
                List.of("入口在左侧", "出口在右侧"),
                fallback.facets("入口在左侧；出口在右侧"));
        assertEquals(
                List.of("First rule", "Second rule"),
                fallback.facets("First rule\nSecond rule"));
    }

    @Test
    void abstainsForImplicitSingleDuplicateOrTooManyClauses() {
        assertTrue(fallback.facets("Use blue and green stripes").isEmpty());
        assertTrue(fallback.facets("same; same").isEmpty());
        assertTrue(fallback.facets("one; two; three; four").isEmpty());
        assertTrue(fallback.facets("one;").isEmpty());
    }
}
