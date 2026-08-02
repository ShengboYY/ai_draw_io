package org.zipp.ai.application.memory;

/** Strict, bounded model output before tenant scope and content policy are applied. */
public record AutoMemoryExtractionDraft(
        MemoryScopeType scopeType,
        AutoMemoryType type,
        String semanticKey,
        String title,
        String canonicalText,
        double confidence
) {
    public AutoMemoryExtractionDraft {
        if (scopeType == null || type == null) {
            throw new IllegalArgumentException("draft scope and type must not be null");
        }
        required(semanticKey, "semanticKey");
        required(title, "title");
        required(canonicalText, "canonicalText");
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
