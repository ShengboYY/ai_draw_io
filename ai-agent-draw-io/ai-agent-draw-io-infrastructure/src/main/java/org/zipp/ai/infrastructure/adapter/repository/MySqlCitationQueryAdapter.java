package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.citation.model.valobj.CellCitationView;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.citation.port.CitationQueryPort;
import org.zipp.ai.infrastructure.dao.grounding.CellCitationRowPO;
import org.zipp.ai.infrastructure.dao.grounding.ICitationQueryMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

/** MySQL read adapter that never returns rows outside the caller's diagram ownership. */
@Repository
public class MySqlCitationQueryAdapter implements CitationQueryPort {
    private final ICitationQueryMapper mapper;
    private final ObjectProvider<RevisionArtifactPort> artifacts;

    public MySqlCitationQueryAdapter(ICitationQueryMapper mapper,
            @Qualifier("materialRagRevisionArtifactPort") ObjectProvider<RevisionArtifactPort> artifacts) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public List<CellCitationView> findCellCitations(String ownerKey, String diagramId,
                                                    String cellId, Long canvasVersion, String provenanceRef) {
        LinkedHashMap<String, CitationBuilder> grouped = new LinkedHashMap<>();
        for (CellCitationRowPO row : mapper.selectCellCitations(
                ownerKey, diagramId, cellId, canvasVersion, provenanceRef)) {
            CitationBuilder citation = grouped.computeIfAbsent(row.getCitationId(), ignored -> new CitationBuilder(row));
            if (row.getCitationKey() != null) citation.sources.add(new CellCitationView.CitationSourceView(
                    row.getCitationKey(), row.getMaterialId(), row.getDisplayName(), row.getVersionId(),
                    row.getVersionNo(), row.getPageNumber(), row.getModality(), row.getSourceState(),
                    row.getProcessingRevisionId(), row.getBboxJson(), row.getOrigin(),
                    Boolean.TRUE.equals(row.getExcerptAvailable()), Boolean.TRUE.equals(row.getPreviewAvailable()),
                    boundedExcerpt(row), previewUrl(row), row.getDeletedAt()));
        }
        return grouped.values().stream().map(CitationBuilder::build).toList();
    }

    private String boundedExcerpt(CellCitationRowPO row) {
        RevisionArtifactPort reader = artifacts.getIfAvailable();
        if (reader == null || row.getDisplayTextObjectKey() == null
                || row.getDisplayTextObjectVersionId() == null || row.getMaterialId() == null) return null;
        try {
            var artifact = reader.findImmutable(row.getDisplayTextObjectKey(), "text/plain", 64 * 1024L)
                    .filter(value -> row.getDisplayTextObjectVersionId().equals(value.objectVersionId()))
                    .orElse(null);
            if (artifact == null) return null;
            String text = new String(reader.read(artifact, 64 * 1024L), StandardCharsets.UTF_8)
                    .replaceAll("[\\r\\n\\t]+", " ").trim();
            return text.length() <= 800 ? text : text.substring(0, 800);
        } catch (RuntimeException unavailable) {
            // Citation metadata remains useful when the optional bounded content read is unavailable.
            return null;
        }
    }

    private String previewUrl(CellCitationRowPO row) {
        if (!Boolean.TRUE.equals(row.getPreviewAvailable()) || row.getMaterialId() == null
                || row.getVersionId() == null || row.getPageNumber() == null) return null;
        String revision = row.getProcessingRevisionId() == null ? ""
                : "?revisionId=" + encode(row.getProcessingRevisionId());
        return "/api/v1/materials/" + encode(row.getMaterialId()) + "/versions/"
                + encode(row.getVersionId()) + "/pages/" + row.getPageNumber() + "/preview" + revision;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class CitationBuilder {
        private final CellCitationRowPO row;
        private final List<CellCitationView.CitationSourceView> sources = new ArrayList<>();

        private CitationBuilder(CellCitationRowPO row) { this.row = row; }

        private CellCitationView build() {
            return new CellCitationView(row.getCitationId(), row.getCellId(), row.getProvenanceRef(), row.getStatementKey(),
                    SupportType.valueOf(row.getSupportType()), row.getCitationState(), sources);
        }
    }
}
