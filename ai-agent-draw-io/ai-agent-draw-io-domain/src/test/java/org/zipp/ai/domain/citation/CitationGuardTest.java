package org.zipp.ai.domain.citation;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.citation.model.aggregate.SourceCitation;
import org.zipp.ai.domain.citation.model.valobj.CitationState;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.citation.service.CitationGuard;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CitationGuardTest {

    @Test
    void evidenceBindingMustComeFromThePreparedBundleWhitelist() {
        SourceCitation citation = SourceCitation.forDiagramCell(
                "cit_1", "usr_1", "dia_1", 4L, "cell_7", SupportType.EVIDENCE);
        CitationGuard guard = new CitationGuard(Set.of("ev_allowed"));

        assertEquals(CitationState.NEEDS_REVIEW, citation.state());
        guard.bind(citation, "ev_allowed", "ver_1", "rev_1", "support:1");
        citation.verify();
        assertEquals(CitationState.VERIFIED, citation.state());
        assertThrows(IllegalArgumentException.class,
                () -> guard.bind(citation, "ev_other", "ver_1", "rev_1", "support:2"));
    }

    @Test
    void deletingEvidenceDegradesCitationWithoutRetainingSourceContent() {
        SourceCitation citation = SourceCitation.forDiagramCell(
                "cit_1", "usr_1", "dia_1", 4L, "cell_7", SupportType.EVIDENCE);
        CitationGuard guard = new CitationGuard(Set.of("ev_1", "ev_2"));
        guard.bind(citation, "ev_1", "ver_1", "rev_1", "support:1");
        guard.bind(citation, "ev_2", "ver_1", "rev_1", "support:2");

        citation.tombstoneEvidence("ev_1", "mat_1", "ver_1", 1);
        assertEquals(CitationState.PARTIAL_SOURCE_UNAVAILABLE, citation.state());
        citation.tombstoneEvidence("ev_2", "mat_1", "ver_1", 1);
        assertEquals(CitationState.SOURCE_UNAVAILABLE, citation.state());
    }
}
