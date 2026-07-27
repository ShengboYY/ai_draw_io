package org.zipp.ai.application.memory;

public record ConfirmedMemoryEditCommand(ConfirmedMemoryFence fence, String canonicalText) {
    public ConfirmedMemoryEditCommand {
        if (fence == null || canonicalText == null || canonicalText.isBlank()) {
            throw new IllegalArgumentException("memory edit must contain a fence and text");
        }
    }
}
