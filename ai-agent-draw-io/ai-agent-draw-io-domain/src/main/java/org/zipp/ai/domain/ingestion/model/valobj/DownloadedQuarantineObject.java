package org.zipp.ai.domain.ingestion.model.valobj;

import java.nio.file.Path;

public record DownloadedQuarantineObject(String objectRef, Path path, long byteSize, String contentSha256) {
    public DownloadedQuarantineObject {
        if (objectRef == null || objectRef.isBlank() || path == null || byteSize < 1
                || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("a complete downloaded quarantine object is required");
        }
    }
}
