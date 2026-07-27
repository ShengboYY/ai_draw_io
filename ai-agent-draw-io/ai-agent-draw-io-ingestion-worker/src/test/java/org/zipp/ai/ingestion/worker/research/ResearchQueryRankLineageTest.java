package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchQueryRankLineageTest {

    @Test
    void shouldExposeOrderedLanePoolsAndBothRanksForEveryStabilizedCandidate() {
        Map<String, Object> trace = ResearchQueryRankLineage.trace(
                List.of("shared", "original-only", "late"),
                List.of("rewritten-only", "shared", "late"),
                List.of("shared", "rewritten-only", "original-only"));

        assertEquals(List.of("shared", "original-only", "late"), trace.get("originalTop80ChunkIds"));
        assertEquals(List.of("rewritten-only", "shared", "late"), trace.get("rewrittenTop80ChunkIds"));
        assertEquals(List.of(
                Map.of("rank", 1, "chunkId", "shared", "originalRank", 1, "rewrittenRank", 2),
                Map.of("rank", 2, "chunkId", "rewritten-only", "rewrittenRank", 1),
                Map.of("rank", 3, "chunkId", "original-only", "originalRank", 2)
        ), trace.get("stabilizedTop40"));
    }
}
