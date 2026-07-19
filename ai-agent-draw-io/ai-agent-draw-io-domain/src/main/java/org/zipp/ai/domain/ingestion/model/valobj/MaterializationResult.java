package org.zipp.ai.domain.ingestion.model.valobj;

public record MaterializationResult(Outcome outcome, String materialId, String versionId, String revisionId) {
    public enum Outcome { COMPLETED, REUSED_VERSION, EXTRACTION_QUEUED, PROMOTION_QUEUED }

    public MaterializationResult {
        if (outcome == null || isBlank(materialId) || isBlank(versionId) || isBlank(revisionId)) {
            throw new IllegalArgumentException("complete materialization result is required");
        }
        materialId = materialId.trim();
        versionId = versionId.trim();
        revisionId = revisionId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
