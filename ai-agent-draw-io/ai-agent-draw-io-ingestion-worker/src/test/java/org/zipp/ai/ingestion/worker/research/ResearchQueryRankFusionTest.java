package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchQueryRankFusionTest {

    @Test
    void shouldPromoteEvidenceFoundByBothQueryLanes() {
        List<String> fused = ResearchQueryRankFusion.fuse(
                List.of("original-only", "shared"),
                List.of("rewritten-only", "shared"),
                3);

        assertEquals(List.of("shared", "original-only", "rewritten-only"), fused);
    }

    @Test
    void shouldBreakEqualScoreAndRankTiesByVectorId() {
        assertEquals(List.of("a-vector", "b-vector"), ResearchQueryRankFusion.fuse(
                List.of("b-vector"), List.of("a-vector"), 2));
    }

    @Test
    void shouldDeduplicateRepeatedVectorIdsWithinOneLane() {
        assertEquals(List.of("shared", "other"), ResearchQueryRankFusion.fuse(
                List.of("shared", "shared", "other"), List.of("shared"), 2));
    }
}
