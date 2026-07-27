package org.zipp.ai.domain.ingestion.model.valobj;

public record MaterializationIds(String materialId, String versionId, String contentBlobId,
                                 String revisionId, String scopeLinkId) {
    public MaterializationIds {
        materialId = requireText(materialId, "materialId");
        versionId = requireText(versionId, "versionId");
        contentBlobId = requireText(contentBlobId, "contentBlobId");
        revisionId = requireText(revisionId, "revisionId");
        scopeLinkId = requireText(scopeLinkId, "scopeLinkId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
