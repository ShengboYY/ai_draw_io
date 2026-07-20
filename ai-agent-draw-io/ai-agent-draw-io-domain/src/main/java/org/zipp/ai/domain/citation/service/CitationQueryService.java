package org.zipp.ai.domain.citation.service;

import org.zipp.ai.domain.citation.model.valobj.CellCitationView;
import org.zipp.ai.domain.citation.model.valobj.AnswerCitationView;
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

    public List<AnswerCitationView> findAnswers(String ownerKey, String diagramId, List<String> messageIds) {
        if (blank(ownerKey) || blank(diagramId)) {
            throw new IllegalArgumentException("ownerKey and diagramId are required");
        }
        List<String> normalized = (messageIds == null ? List.<String>of() : messageIds).stream()
                .filter(id -> !blank(id)).distinct().toList();
        if (normalized.isEmpty()) return List.of();
        java.util.ArrayList<AnswerCitationView> result = new java.util.ArrayList<>();
        for (int start = 0; start < normalized.size(); start += 200) {
            result.addAll(queryPort.findAnswerCitations(ownerKey.trim(), diagramId.trim(),
                    normalized.subList(start, Math.min(start + 200, normalized.size()))));
        }
        return List.copyOf(result);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
