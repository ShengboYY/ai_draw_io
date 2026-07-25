package org.zipp.ai.application.turn.context;

public record ConflictingContext<T>(String conflictRef) implements ContextRead<T> {

    public ConflictingContext {
        if (conflictRef == null || conflictRef.isBlank()) {
            throw new IllegalArgumentException("conflictRef must not be blank");
        }
    }
}
