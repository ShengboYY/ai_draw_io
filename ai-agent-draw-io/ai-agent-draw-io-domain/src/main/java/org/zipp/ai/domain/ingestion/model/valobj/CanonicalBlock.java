package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record CanonicalBlock(String blockId, TextBlockKind kind, int readingOrder,
                             List<NormalizedBoundingBox> regions, TextSource textSource,
                             String extractedText, String displayText,
                             List<SourceMapSpan> sourceMap, double confidence,
                             BoilerplatePosition boilerplatePosition) {
    public CanonicalBlock {
        if (blockId == null || blockId.isBlank() || readingOrder < 1
                || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("canonical block identity is invalid");
        }
        kind = Objects.requireNonNull(kind, "kind");
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        textSource = Objects.requireNonNull(textSource, "textSource");
        boilerplatePosition = Objects.requireNonNull(boilerplatePosition, "boilerplatePosition");
        extractedText = Objects.requireNonNull(extractedText, "extractedText");
        displayText = Objects.requireNonNull(displayText, "displayText");
        sourceMap = List.copyOf(Objects.requireNonNull(sourceMap, "sourceMap"));
        int displayCursor = 0;
        for (SourceMapSpan span : sourceMap) {
            if (span.displayStart() != displayCursor || span.displayEnd() > displayText.length()
                    || span.extractedEnd() > extractedText.length()) {
                throw new IllegalArgumentException("source map must exactly cover valid canonical offsets");
            }
            displayCursor = span.displayEnd();
        }
        if (displayCursor != displayText.length()) {
            throw new IllegalArgumentException("source map must cover all display text");
        }
    }
}
