package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Durable output of native extraction for one page. */
public record NativePageResult(int pageNo, double width, double height, boolean ocrRequired,
                               StoredArtifact pageImage, StoredArtifact nativeExtraction,
                               StoredArtifact rawExtraction) {
    public NativePageResult {
        if (pageNo < 1 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("page identity and dimensions are invalid");
        }
        pageImage = Objects.requireNonNull(pageImage, "pageImage");
        nativeExtraction = Objects.requireNonNull(nativeExtraction, "nativeExtraction");
        if (ocrRequired == (rawExtraction != null)) {
            throw new IllegalArgumentException("raw extraction is required exactly when OCR is not required");
        }
    }
}
