package org.zipp.ai.application.memory;

/** Owner/scope/version fence required by every user management action. */
public record AutoMemoryFence(
        AutoMemoryScope scope,
        String memoryId,
        long expectedVersion
) {
    public AutoMemoryFence {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (memoryId == null || memoryId.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }
}
