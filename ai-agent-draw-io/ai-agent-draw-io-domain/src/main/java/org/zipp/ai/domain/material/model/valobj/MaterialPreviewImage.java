package org.zipp.ai.domain.material.model.valobj;

public record MaterialPreviewImage(byte[] bytes, String contentType, String contentSha256) {
    public MaterialPreviewImage {
        bytes = bytes == null ? null : bytes.clone();
        if (bytes == null || bytes.length == 0 || contentType == null || contentType.isBlank()
                || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("material preview image is invalid");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
