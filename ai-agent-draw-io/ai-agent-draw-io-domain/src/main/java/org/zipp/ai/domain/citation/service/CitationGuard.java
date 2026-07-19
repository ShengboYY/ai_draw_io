package org.zipp.ai.domain.citation.service;

import org.zipp.ai.domain.citation.model.aggregate.SourceCitation;
import org.zipp.ai.domain.citation.model.entity.CitationEvidence;

import java.util.Set;

public final class CitationGuard {

    private final Set<String> allowedEvidenceIds;

    public CitationGuard(Set<String> allowedEvidenceIds) {
        this.allowedEvidenceIds = Set.copyOf(allowedEvidenceIds);
    }

    public void bind(SourceCitation citation, String evidenceId, String versionId,
                     String revisionId, String citationKey) {
        if (!allowedEvidenceIds.contains(evidenceId)) {
            throw new IllegalArgumentException("evidence was not part of the prepared bundle");
        }
        citation.bindEvidence(new CitationEvidence(evidenceId, versionId, revisionId, citationKey));
    }
}
