package org.zipp.ai.application.turn.context;

public record TruncatedContext<T>(T value, String provenance, String truncationReceipt)
        implements ContextRead<T> {

    public TruncatedContext {
        if (value == null || provenance == null || provenance.isBlank()
                || truncationReceipt == null || truncationReceipt.isBlank()) {
            throw new IllegalArgumentException("truncated context values must not be blank");
        }
    }
}
