package org.zipp.ai.api.dto;

import java.util.List;

public record CellCitationDTO(String citationId, String cellId, String provenanceRef, String statementKey,
                              String supportType, String state, List<SourceDTO> sources) {
    public record SourceDTO(String citationKey, String materialId, String displayName,
                            String versionId, Integer versionNo, Integer pageNumber,
                            String modality, String sourceState,
                            String processingRevisionId, String bboxJson,
                            String origin, boolean excerptAvailable,
                            boolean previewAvailable, String boundedExcerpt,
                            String previewUrl, String deletedAt) { }
}
