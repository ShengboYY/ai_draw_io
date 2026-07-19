package org.zipp.ai.domain.ingestion.model.valobj;

public record ParsedDocument(int pageCount) {
    public ParsedDocument {
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
    }
}
