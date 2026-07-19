package org.zipp.ai.domain.ingestion.model.valobj;

public record PromotedOriginal(String objectKey, String objectVersionId, String eTag,
                               String checksumSha256, long byteSize) {
    public PromotedOriginal {
        objectKey = requireText(objectKey, "objectKey");
        objectVersionId = requireText(objectVersionId, "objectVersionId");
        eTag = trimToNull(eTag);
        checksumSha256 = requireText(checksumSha256, "checksumSha256");
        if (byteSize < 1) {
            throw new IllegalArgumentException("byteSize must be positive");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
