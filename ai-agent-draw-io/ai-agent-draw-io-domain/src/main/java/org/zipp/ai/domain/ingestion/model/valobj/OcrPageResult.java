package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record OcrPageResult(int pageNo, double meanConfidence, boolean lowConfidence,
                            StoredArtifact rawExtraction) {
    public OcrPageResult {
        if (pageNo < 1 || meanConfidence < 0 || meanConfidence > 1) {
            throw new IllegalArgumentException("OCR page facts are invalid");
        }
        rawExtraction = Objects.requireNonNull(rawExtraction, "rawExtraction");
    }
}
