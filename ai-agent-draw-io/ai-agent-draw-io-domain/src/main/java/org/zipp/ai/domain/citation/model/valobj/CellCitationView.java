package org.zipp.ai.domain.citation.model.valobj;

import java.util.List;

/** Owner-authorized citation projection for one semantic canvas statement. */
public record CellCitationView(String citationId, String cellId, String provenanceRef, String statementKey,
                               SupportType supportType, String state,
                               List<CitationSourceView> sources) {
    public CellCitationView {
        sources = List.copyOf(sources == null ? List.of() : sources);
    }

    public record CitationSourceView(String citationKey, String materialId, String displayName,
                                     String versionId, Integer versionNo, Integer pageNumber,
                                     String modality, String sourceState,
                                     String processingRevisionId, String bboxJson,
                                     String origin, boolean excerptAvailable,
                                     boolean previewAvailable, String boundedExcerpt,
                                     String previewUrl, String deletedAt) { }
}
