package org.zipp.ai.api.dto;

public record MaterialReprocessResponseDTO(String materialId, String versionId, String revisionId,
                                           int revisionNo, String processingStatus, boolean reused) {
}
