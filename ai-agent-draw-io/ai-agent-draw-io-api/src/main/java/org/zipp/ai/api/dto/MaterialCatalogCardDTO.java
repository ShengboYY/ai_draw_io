package org.zipp.ai.api.dto;

import java.time.Instant;

public record MaterialCatalogCardDTO(String materialId, String kind, String displayName,
                                     String retentionClass, String lifecycleState,
                                     String latestVersionId, Integer latestVersionNo,
                                     String processingStatus, int progress,
                                     String searchStatus,
                                     Integer pageCount, Instant updatedAt) {
}
