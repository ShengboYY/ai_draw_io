package org.zipp.ai.application.turn.context;

public record AbsentContext<T>(String reason) implements ContextRead<T> {

    public AbsentContext {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
