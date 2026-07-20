package org.zipp.ai.domain.material.model.valobj;

import java.time.Instant;

/** Metadata-only material card; it never contains extracted text or storage coordinates. */
public record MaterialCatalogItem(String materialId, MaterialKind kind, String displayName,
                                  RetentionClass retentionClass, MaterialLifecycleState lifecycleState,
                                  String latestVersionId, Integer latestVersionNo,
                                  CatalogProcessingStatus processingStatus, int progress,
                                  Integer pageCount, Instant updatedAt) {
    public MaterialCatalogItem {
        if (materialId == null || materialId.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("material catalog identity is required");
        }
        if (kind == null || retentionClass == null || lifecycleState == null || processingStatus == null
                || progress < 0 || progress > 100) {
            throw new IllegalArgumentException("material catalog card is invalid");
        }
        materialId = materialId.trim();
        displayName = displayName.trim();
    }
}
