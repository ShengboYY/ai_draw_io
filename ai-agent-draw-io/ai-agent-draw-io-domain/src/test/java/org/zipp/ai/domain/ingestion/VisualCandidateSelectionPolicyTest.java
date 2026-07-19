package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentSection;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructure;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCandidate;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualCandidateSelectionPolicyTest {

    @Test
    void boundsSelectedPagesAndRegionsWithoutHidingSkippedCandidates() {
        List<VisualCandidate> candidates = new ArrayList<>();
        for (int pageNo = 1; pageNo <= 6; pageNo++) {
            for (int region = 1; region <= 4; region++) {
                candidates.add(candidate(pageNo, region, region == 1 ? "caption_" + pageNo : null));
            }
        }
        var structure = structure(candidates);
        var policy = new VisualCandidateSelectionPolicy(12, 0.15, 3);

        var selection = policy.select(structure, 20);

        assertEquals(3, selection.selectedPageCount());
        assertEquals(9, selection.selectedCandidates().size());
        assertEquals(24, selection.totalCandidateCount());
        assertEquals(15, selection.skippedCandidateCount());
        assertTrue(selection.selectedCandidates().stream().map(VisualCandidate::pageNo).distinct().count() <= 3);
    }

    @Test
    void captionedAndLargerCandidatesWinDeterministicBudgetTies() {
        VisualCandidate uncaptioned = new VisualCandidate("vis_a", 1,
                List.of(new NormalizedBoundingBox(0.1, 0.1, 0.9, 0.9)), null);
        VisualCandidate captioned = new VisualCandidate("vis_b", 2,
                List.of(new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.2)), "caption_2");
        var policy = new VisualCandidateSelectionPolicy(1, 0.01, 3);

        var selection = policy.select(structure(List.of(uncaptioned, captioned)), 2);

        assertEquals(List.of(captioned), selection.selectedCandidates());
        assertTrue(policy.fingerprint().contains("max-pages=1"));
    }

    private static VisualCandidate candidate(int pageNo, int region, String caption) {
        double start = region * 0.1;
        return new VisualCandidate("vis_" + pageNo + "_" + region, pageNo,
                List.of(new NormalizedBoundingBox(start, 0.1, start + 0.08, 0.3)), caption);
    }

    private static DocumentStructure structure(List<VisualCandidate> candidates) {
        return new DocumentStructure("document-structure-v1",
                List.of(new DocumentSection("sec_1", null, 1, 1, 1, 20, null, "a".repeat(64))),
                List.of(), candidates, "b".repeat(64));
    }
}
