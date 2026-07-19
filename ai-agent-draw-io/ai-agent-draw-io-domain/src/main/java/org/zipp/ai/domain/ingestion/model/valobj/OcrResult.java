package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record OcrResult(int pageNo, String text, double confidence, List<OcrWord> words) {
    public OcrResult {
        if (pageNo < 1 || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("invalid OCR result");
        }
        text = text == null ? "" : text;
        words = List.copyOf(Objects.requireNonNull(words, "words"));
    }
}
