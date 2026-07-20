package org.zipp.ai.domain.citation.service;

import org.zipp.ai.domain.citation.model.valobj.CellCitationView;
import org.zipp.ai.domain.citation.port.CitationQueryPort;

import java.util.List;
import java.util.Objects;

/** Application-facing domain service for owner-fenced canvas citation reads. */
public final class CitationQueryService {
    private final CitationQueryPort queryPort;

    public CitationQueryService(CitationQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
    }

    public List<CellCitationView> find(String ownerKey, String diagramId, String cellId,
                                       Long canvasVersion, String provenanceRef) {
        if (blank(ownerKey) || blank(diagramId) || blank(cellId)) {
            throw new IllegalArgumentException("ownerKey, diagramId and cellId are required");
        }
        if (canvasVersion != null && canvasVersion < 1) {
            throw new IllegalArgumentException("canvasVersion must be positive");
        }
        String expectedRef = blank(provenanceRef) ? null : provenanceRef.trim();
        if (expectedRef != null && !expectedRef.matches("prv_[a-f0-9]{24}")) {
            throw new IllegalArgumentException("provenanceRef is invalid");
        }
        return queryPort.findCellCitations(ownerKey.trim(), diagramId.trim(), cellId.trim(),
                canvasVersion, expectedRef);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
