package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

import java.util.Objects;

public record UploadAdmissionRequest(OwnerType ownerType,
                                     UploadTarget target,
                                     String declaredMediaType,
                                     long declaredBytes,
                                     long accountOriginalBytes,
                                     int activeFileCount,
                                     int processingCount,
                                     int workspaceHourlyCount,
                                     int ipHourlyCount,
                                     int batchFileCount) {

    public UploadAdmissionRequest {
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(target, "target");
        if (declaredMediaType == null || declaredMediaType.isBlank()) {
            throw new IllegalArgumentException("declaredMediaType is required");
        }
        declaredMediaType = declaredMediaType.trim().toLowerCase(java.util.Locale.ROOT);
        if (declaredBytes < 1 || accountOriginalBytes < 0 || activeFileCount < 0
                || processingCount < 0 || workspaceHourlyCount < 0 || ipHourlyCount < 0
                || batchFileCount < 1) {
            throw new IllegalArgumentException("upload admission counters cannot be negative");
        }
    }
}
