package org.zipp.ai.domain.ingestion.model.valobj;

public record MaterialObject(String objectKey, String contentSha256, byte[] content) {

    public MaterialObject {
        objectKey = requireText(objectKey, "objectKey");
        contentSha256 = requireText(contentSha256, "contentSha256");
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
