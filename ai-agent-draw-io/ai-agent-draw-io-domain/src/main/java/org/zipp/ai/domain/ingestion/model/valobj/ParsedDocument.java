package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record ParsedDocument(List<ParsedPage> pages) {
    public ParsedDocument {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("parsed document requires at least one page");
        }
    }

    public int pageCount() {
        return pages.size();
    }
}
