package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record SourceMapSpan(int displayStart, int displayEnd, int extractedStart, int extractedEnd,
                            List<NormalizedBoundingBox> regions) {
    public SourceMapSpan {
        if (displayStart < 0 || displayEnd < displayStart || extractedStart < 0
                || extractedEnd < extractedStart) {
            throw new IllegalArgumentException("source map offsets are invalid");
        }
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("source map requires source regions");
        }
    }
}
