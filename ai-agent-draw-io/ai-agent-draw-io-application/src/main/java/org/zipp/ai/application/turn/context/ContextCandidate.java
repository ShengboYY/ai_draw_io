package org.zipp.ai.application.turn.context;

/** Live candidate read-set assembled only after the durable read-set was found missing. */
public record ContextCandidate(ContextReadSet readSet) {

    public ContextCandidate {
        if (readSet == null) {
            throw new IllegalArgumentException("context candidate read-set must not be null");
        }
    }
}
