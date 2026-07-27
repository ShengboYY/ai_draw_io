package org.zipp.ai.application.turn.context;

public record DegradedContext<T>(String diagnostic) implements ContextRead<T> {

    public DegradedContext {
        if (diagnostic == null || diagnostic.isBlank()) {
            throw new IllegalArgumentException("diagnostic must not be blank");
        }
    }
}
