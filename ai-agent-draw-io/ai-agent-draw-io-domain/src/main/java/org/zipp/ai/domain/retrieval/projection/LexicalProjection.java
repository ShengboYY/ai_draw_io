package org.zipp.ai.domain.retrieval.projection;

import java.util.List;
import java.util.Objects;

public record LexicalProjection(String chunkId, String wordSearchText, String cjkSearchText,
                                List<ExactTerm> exactTerms) {
    public LexicalProjection {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("lexical projection chunk is required");
        }
        wordSearchText = blankToNull(wordSearchText);
        cjkSearchText = blankToNull(cjkSearchText);
        exactTerms = List.copyOf(Objects.requireNonNull(exactTerms, "exactTerms"));
        if (wordSearchText == null && cjkSearchText == null && exactTerms.isEmpty()) {
            throw new IllegalArgumentException("lexical projection must contain a search signal");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ExactTerm(String normalizedTerm, String termType) {
        public ExactTerm {
            if (normalizedTerm == null || normalizedTerm.isBlank()
                    || normalizedTerm.length() > 255 || termType == null || termType.isBlank()) {
                throw new IllegalArgumentException("exact term identity is invalid");
            }
        }
    }
}
