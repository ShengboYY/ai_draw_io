package org.zipp.ai.application.turn;

/** Bounded model result for a response that has no Canvas mutation. */
public record PlainResponseGenerationResult(String assistantMessage, String payloadRef) {

    public PlainResponseGenerationResult {
        assistantMessage = bounded(assistantMessage, "assistantMessage", 16_000);
        payloadRef = bounded(payloadRef, "payloadRef", 255);
    }

    private static String bounded(String value, String field, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
