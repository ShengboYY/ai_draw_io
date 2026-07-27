package org.zipp.ai.domain.ingestion.model.valobj;

public record OwnedContentBlob(String id, String status, String originalObjectKey) {
    public OwnedContentBlob {
        id = requireText(id, "id");
        status = requireText(status, "status");
        originalObjectKey = requireText(originalObjectKey, "originalObjectKey");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
