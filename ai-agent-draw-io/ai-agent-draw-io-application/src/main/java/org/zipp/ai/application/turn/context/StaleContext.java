package org.zipp.ai.application.turn.context;

public record StaleContext<T>(String provenance, String diagnostic) implements ContextRead<T> {

    public StaleContext {
        if (provenance == null || provenance.isBlank()
                || diagnostic == null || diagnostic.isBlank()) {
            throw new IllegalArgumentException("stale context values must not be blank");
        }
    }
}
