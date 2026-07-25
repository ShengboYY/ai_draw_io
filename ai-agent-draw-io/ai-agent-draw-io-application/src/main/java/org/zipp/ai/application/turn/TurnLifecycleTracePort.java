package org.zipp.ai.application.turn;

/** Outbound port for redacted turn lifecycle evidence. Implementations must not add raw content. */
@FunctionalInterface
public interface TurnLifecycleTracePort {

    void record(TurnLifecycleTraceEvent event);

    /** Trace failures must never turn a durable turn result into a delivery failure. */
    default void recordSafely(TurnLifecycleTraceEvent event) {
        try {
            record(event);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort; lifecycle ownership remains authoritative elsewhere.
        }
    }
}
