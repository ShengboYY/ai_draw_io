package org.zipp.ai.application.turn.context;

/** A closed read state prevents unavailable context from being represented as an empty value. */
public sealed interface ContextRead<T>
        permits AvailableContext, TruncatedContext, DegradedContext,
        AbsentContext, StaleContext, ConflictingContext {
}
