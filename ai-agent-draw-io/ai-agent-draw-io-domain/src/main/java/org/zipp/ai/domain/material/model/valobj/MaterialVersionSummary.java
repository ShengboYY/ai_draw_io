package org.zipp.ai.domain.material.model.valobj;

import java.time.Instant;

/** Safe catalog projection for one immutable material version. */
public record MaterialVersionSummary(String versionId, int versionNo, String detectedMime,
                                     long byteSize, Integer pageCount,
                                     CatalogProcessingStatus processingStatus,
                                     int progress, Instant createdAt) {
    public MaterialVersionSummary {
        if (versionId == null || versionId.isBlank()) throw new IllegalArgumentException("versionId is required");
        if (versionNo < 1 || byteSize < 0 || processingStatus == null
                || progress < 0 || progress > 100) {
            throw new IllegalArgumentException("material version summary is invalid");
        }
        versionId = versionId.trim();
    }
}
