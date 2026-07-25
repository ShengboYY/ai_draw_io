package org.zipp.ai.application.turn.context;

public record AvailableContext<T>(T value, String provenance) implements ContextRead<T> {

    public AvailableContext {
        if (value == null || provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("available context values must not be blank");
        }
    }
}
