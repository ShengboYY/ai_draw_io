package org.zipp.ai.application.turn;

/** Default trace sink for deployments that have not selected a durable/observability adapter. */
public enum NoopTurnLifecycleTracePort implements TurnLifecycleTracePort {
    INSTANCE;

    @Override
    public void record(TurnLifecycleTraceEvent event) {
        // Keep the application graph usable when lifecycle observability is intentionally absent.
    }
}
