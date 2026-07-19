package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record ExtractedTextBlock(String blockId, TextBlockKind kind, int readingOrder,
                                 List<NormalizedBoundingBox> regions, TextSource textSource,
                                 String text, List<SourceMapSpan> sourceMap, double confidence) {
    public ExtractedTextBlock {
        blockId = requireText(blockId, "blockId");
        kind = Objects.requireNonNull(kind, "kind");
        if (readingOrder < 1 || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("reading order and confidence are invalid");
        }
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("a text block requires at least one source region");
        }
        textSource = Objects.requireNonNull(textSource, "textSource");
        text = requireText(text, "text");
        sourceMap = List.copyOf(Objects.requireNonNull(sourceMap, "sourceMap"));
        if (sourceMap.isEmpty()) {
            throw new IllegalArgumentException("an extracted text block requires a source map");
        }
        int sourceCursor = 0;
        for (SourceMapSpan span : sourceMap) {
            if (span.extractedStart() != sourceCursor || span.displayStart() != sourceCursor
                    || span.displayEnd() != span.extractedEnd() || span.extractedEnd() > text.length()) {
                throw new IllegalArgumentException("raw text source map must exactly cover extracted text");
            }
            sourceCursor = span.extractedEnd();
        }
        if (sourceCursor != text.length()) {
            throw new IllegalArgumentException("raw text source map must cover all extracted text");
        }
    }

    public ExtractedTextBlock(String blockId, TextBlockKind kind, int readingOrder,
                              List<NormalizedBoundingBox> regions, TextSource textSource,
                              String text, double confidence) {
        this(blockId, kind, readingOrder, regions, textSource, text,
                List.of(new SourceMapSpan(0, text.length(), 0, text.length(), regions)), confidence);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
