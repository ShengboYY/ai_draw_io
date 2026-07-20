package org.zipp.ai.domain.material.model.valobj;

public record MaterialObjectVersion(String bucket, String objectKey, String objectVersionId) {
    public MaterialObjectVersion {
        bucket = required(bucket, "bucket");
        objectKey = required(objectKey, "objectKey");
        objectVersionId = required(objectVersionId, "objectVersionId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
