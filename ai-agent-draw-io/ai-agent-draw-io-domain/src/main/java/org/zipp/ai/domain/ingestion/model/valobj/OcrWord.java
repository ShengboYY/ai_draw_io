package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record OcrWord(String text, NormalizedBoundingBox region, double confidence, String lineId) {
    public OcrWord {
        if (text == null || text.isBlank() || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("OCR word text and confidence are invalid");
        }
        region = Objects.requireNonNull(region, "region");
        if (lineId == null || lineId.isBlank()) {
            throw new IllegalArgumentException("lineId is required");
        }
    }
}
