package org.zipp.ai.application.memory;

/** Explicit, retryable user action that converts a pending candidate into confirmed Memory. */
public record MemoryMaterializeCommand(MemoryCandidateFence fence) {
    public MemoryMaterializeCommand {
        if (fence == null) {
            throw new IllegalArgumentException("fence must not be null");
        }
    }
}
