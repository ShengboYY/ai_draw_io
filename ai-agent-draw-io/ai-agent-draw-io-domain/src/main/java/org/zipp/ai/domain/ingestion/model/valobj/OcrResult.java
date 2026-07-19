package org.zipp.ai.domain.ingestion.model.valobj;

public record OcrResult(int pageNo, String text, double confidence) {
    public OcrResult {
        if (pageNo < 1 || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("invalid OCR result");
        }
        text = text == null ? "" : text;
    }
}
