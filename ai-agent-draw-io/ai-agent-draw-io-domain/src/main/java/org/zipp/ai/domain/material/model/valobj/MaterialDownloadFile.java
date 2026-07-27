package org.zipp.ai.domain.material.model.valobj;

import java.util.Objects;

/** Exact original bytes plus safe response metadata; storage identity stays behind the port. */
public record MaterialDownloadFile(String fileName, String contentType, byte[] bytes) {
    public MaterialDownloadFile {
        fileName = safeFileName(fileName);
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType is required");
        }
        contentType = contentType.trim();
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        if (bytes.length == 0) throw new IllegalArgumentException("download bytes cannot be empty");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** HTTP boundary consumes this immutable record immediately, avoiding another large defensive copy. */
    public byte[] responseBytes() {
        return bytes;
    }

    private static String safeFileName(String value) {
        if (value == null || value.isBlank()) return "download";
        String sanitized = value.trim().replaceAll("[\\\\/\\p{Cntrl}]", "_");
        return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180);
    }
}
