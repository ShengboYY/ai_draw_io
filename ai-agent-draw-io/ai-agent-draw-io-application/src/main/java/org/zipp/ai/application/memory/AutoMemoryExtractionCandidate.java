package org.zipp.ai.application.memory;

/** Bounded existing Memory projection available to one extraction request. */
public record AutoMemoryExtractionCandidate(
        MemoryScopeType scopeType,
        AutoMemoryType type,
        String semanticKey,
        String title,
        String canonicalText,
        AutoMemoryStatus status
) {
    public AutoMemoryExtractionCandidate {
        if (scopeType == null || type == null || status == null) {
            throw new IllegalArgumentException("scopeType, type and status must not be null");
        }
        required(semanticKey, "semanticKey");
        required(title, "title");
        required(canonicalText, "canonicalText");
    }

    public static AutoMemoryExtractionCandidate from(AutoMemory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        return new AutoMemoryExtractionCandidate(
                memory.scope().type(),
                memory.type(),
                memory.semanticKey(),
                memory.title(),
                memory.canonicalText(),
                memory.status());
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
