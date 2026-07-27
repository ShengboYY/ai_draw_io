package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record VisualCandidate(String candidateId, int pageNo, List<NormalizedBoundingBox> regions,
                              String captionBlockId) {
    public VisualCandidate {
        if (candidateId == null || candidateId.isBlank() || pageNo < 1) {
            throw new IllegalArgumentException("visual candidate identity is invalid");
        }
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("visual candidate requires a source region");
        }
        captionBlockId = captionBlockId == null || captionBlockId.isBlank() ? null : captionBlockId.trim();
    }
}
