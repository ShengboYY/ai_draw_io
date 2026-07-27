package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchSourceDiversifierTest {

    @Test
    void shouldKeepTheFirstResultAndCapAHeadDominatingSource() {
        List<ResearchSourceDiversifier.Candidate> candidates = IntStream.rangeClosed(1, 12)
                .mapToObj(rank -> candidate("c" + rank, rank <= 7 ? "source-a" : "source-b"))
                .toList();

        List<String> result = ResearchSourceDiversifier.diversify(candidates, 10, 10, 4);

        assertEquals("c1", result.get(0));
        assertEquals(List.of("c1", "c2", "c3", "c4", "c8", "c9", "c10", "c11", "c5", "c6"),
                result);
    }

    @Test
    void shouldRelaxTheCapWhenOnlyOneSourceCanFillTheHead() {
        List<ResearchSourceDiversifier.Candidate> candidates = IntStream.rangeClosed(1, 6)
                .mapToObj(rank -> candidate("c" + rank, "source-a"))
                .toList();

        assertEquals(List.of("c1", "c2", "c3", "c4", "c5", "c6"),
                ResearchSourceDiversifier.diversify(candidates, 6, 6, 2));
    }

    @Test
    void shouldDeduplicateBeforeBalancingSources() {
        List<ResearchSourceDiversifier.Candidate> candidates = List.of(
                candidate("first", "source-a", "same"),
                candidate("duplicate", "source-b", "same"),
                candidate("unique", "source-b", "unique"));

        assertEquals(List.of("first", "unique"),
                ResearchSourceDiversifier.diversify(candidates, 3, 3, 2));
    }

    private ResearchSourceDiversifier.Candidate candidate(String id, String sourceVersion) {
        return candidate(id, sourceVersion, id);
    }

    private ResearchSourceDiversifier.Candidate candidate(String id, String sourceVersion, String textHash) {
        return new ResearchSourceDiversifier.Candidate(
                id, sourceVersion, true, textHash, Set.of(id));
    }
}
