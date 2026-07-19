package org.zipp.ai.domain.ingestion.model.valobj;

public record OwnedMaterialVersion(String materialId, String versionId, String revisionId, String contentBlobId) {
    public OwnedMaterialVersion {
        materialId = requireText(materialId, "materialId");
        versionId = requireText(versionId, "versionId");
        revisionId = requireText(revisionId, "revisionId");
        contentBlobId = requireText(contentBlobId, "contentBlobId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
