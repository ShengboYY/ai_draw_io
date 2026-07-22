package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchEvidenceDeduplicatorTest {

    @Test
    void shouldPreferACitableCandidateWithinAnExactTextFamily() {
        List<String> result = ResearchEvidenceDeduplicator.deduplicate(List.of(
                candidate("profile", false, "same", Set.of("e1")),
                candidate("child", true, "same", Set.of("e1")),
                candidate("other", true, "other", Set.of("e2"))), 2);

        assertEquals(List.of("child", "other"), result);
    }

    @Test
    void shouldCollapseHighEvidenceOverlapAndBackfillFromTheDeeperPool() {
        List<String> result = ResearchEvidenceDeduplicator.deduplicate(List.of(
                candidate("first", true, "a", Set.of("e1", "e2", "e3", "e4", "e5")),
                candidate("duplicate", true, "b", Set.of("e1", "e2", "e3", "e4")),
                candidate("backfill", true, "c", Set.of("e6"))), 2);

        assertEquals(List.of("first", "backfill"), result);
    }

    @Test
    void shouldNotCollapseCandidatesThatOnlyShareAHeaderEvidence() {
        List<String> result = ResearchEvidenceDeduplicator.deduplicate(List.of(
                candidate("first", true, "a", Set.of("header", "body-a")),
                candidate("second", true, "b", Set.of("header", "body-b"))), 2);

        assertEquals(List.of("first", "second"), result);
    }

    private ResearchEvidenceDeduplicator.Candidate candidate(String id, boolean citable,
                                                              String textHash, Set<String> evidenceIds) {
        return new ResearchEvidenceDeduplicator.Candidate(id, citable, textHash, evidenceIds);
    }
}
