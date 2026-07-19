package org.zipp.ai.domain.ingestion.model.valobj;

public record StoredArtifact(String objectKey, String objectVersionId, String contentSha256,
                             long byteSize, String contentType) {
    public StoredArtifact {
        objectKey = requireText(objectKey, "objectKey");
        objectVersionId = requireText(objectVersionId, "objectVersionId");
        contentSha256 = requireText(contentSha256, "contentSha256");
        contentType = requireText(contentType, "contentType");
        if (byteSize < 1) {
            throw new IllegalArgumentException("byteSize must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
