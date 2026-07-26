package org.zipp.ai.application.memory;

/** Optimistic owner fence for user-managed confirmed Memory. */
public record ConfirmedMemoryFence(String ownerKey, String chartbookId, String memoryId, long expectedVersion) {
    public ConfirmedMemoryFence {
        required(ownerKey, "ownerKey");
        required(chartbookId, "chartbookId");
        required(memoryId, "memoryId");
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
