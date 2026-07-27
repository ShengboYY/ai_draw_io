package org.zipp.ai.domain.ingestion.model.valobj;

public record QuarantineObjectVersion(String versionId, String eTag, String checksumSha256, long byteSize) {

    public QuarantineObjectVersion {
        versionId = requireText(versionId, "versionId");
        eTag = requireText(eTag, "eTag");
        checksumSha256 = trimToNull(checksumSha256);
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize cannot be negative");
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
