package org.zipp.ai.application.turn.agent;

/** Best-effort execution trace; observability failure must not fail drawing. */
@FunctionalInterface
public interface PlainAgentTracePort {

    PlainAgentTracePort NOOP = ignored -> {
    };

    void record(PlainAgentTraceEvent event);

    default void recordSafely(PlainAgentTraceEvent event) {
        try {
            record(event);
        } catch (RuntimeException ignored) {
            // Agent execution and final commit remain authoritative when telemetry is unavailable.
        }
    }
}
