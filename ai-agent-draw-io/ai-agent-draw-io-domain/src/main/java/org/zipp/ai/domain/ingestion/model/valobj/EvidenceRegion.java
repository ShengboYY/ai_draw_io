package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record EvidenceRegion(String pageId, int ordinal, NormalizedBoundingBox boundingBox,
                             Integer displayCharStart, Integer displayCharEnd, String sourceBlockRef) {
    public EvidenceRegion {
        if (pageId == null || pageId.isBlank() || ordinal < 1
                || (displayCharStart == null) != (displayCharEnd == null)
                || (displayCharStart != null && (displayCharStart < 0 || displayCharEnd < displayCharStart))) {
            throw new IllegalArgumentException("evidence region identity is invalid");
        }
        boundingBox = Objects.requireNonNull(boundingBox, "boundingBox");
        sourceBlockRef = sourceBlockRef == null || sourceBlockRef.isBlank() ? null : sourceBlockRef.trim();
    }
}
