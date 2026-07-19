package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Exact artifacts required to continue OCR or canonicalization for one page. */
public record RevisionPageWork(int pageNo, double width, double height, OcrPageStatus ocrStatus,
                               StoredArtifact pageImage, StoredArtifact nativeExtraction,
                               StoredArtifact rawExtraction) {
    public RevisionPageWork {
        if (pageNo < 1 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("page identity and dimensions are invalid");
        }
        ocrStatus = Objects.requireNonNull(ocrStatus, "ocrStatus");
        pageImage = Objects.requireNonNull(pageImage, "pageImage");
        nativeExtraction = Objects.requireNonNull(nativeExtraction, "nativeExtraction");
    }

    public boolean requiresOcr() {
        return ocrStatus == OcrPageStatus.REQUIRED;
    }
}
