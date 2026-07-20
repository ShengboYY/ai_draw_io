package org.zipp.ai.domain.material.model.valobj;

public record MaterialReprocessResult(String materialId, String versionId, String revisionId,
                                      int revisionNo, CatalogProcessingStatus processingStatus,
                                      boolean reused) {
    public MaterialReprocessResult {
        if (materialId == null || materialId.isBlank() || versionId == null || versionId.isBlank()
                || revisionId == null || revisionId.isBlank() || revisionNo < 1
                || processingStatus == null) {
            throw new IllegalArgumentException("material reprocess result is invalid");
        }
    }
}
