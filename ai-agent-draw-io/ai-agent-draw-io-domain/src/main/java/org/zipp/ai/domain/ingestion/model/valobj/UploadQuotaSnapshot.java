package org.zipp.ai.domain.ingestion.model.valobj;

public record UploadQuotaSnapshot(long accountOriginalBytes,
                                  int activeFileCount,
                                  int processingCount,
                                  int workspaceHourlyCount,
                                  int ipHourlyCount) {

    public UploadQuotaSnapshot {
        if (accountOriginalBytes < 0 || activeFileCount < 0 || processingCount < 0
                || workspaceHourlyCount < 0 || ipHourlyCount < 0) {
            throw new IllegalArgumentException("upload quota counters cannot be negative");
        }
    }

    public static UploadQuotaSnapshot empty() {
        return new UploadQuotaSnapshot(0L, 0, 0, 0, 0);
    }
}
