package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;

/**
 * Opens a telemetry run for an attempt no request thread owns.
 *
 * <p>Product turns inherit their run from the HTTP ingress. A recovered attempt has no such
 * caller, so without this port every model call it makes is unattributable and silently drops
 * out of the trace instead of failing.</p>
 */
public interface TurnRecoveryTelemetryPort {

    TurnRecoveryTelemetryPort NOOP = attempt -> TurnRecoveryRun.NOOP;

    /** Opens the run and binds it to the calling thread until the returned handle is closed. */
    TurnRecoveryRun open(FencedAttempt attempt);

    interface TurnRecoveryRun extends AutoCloseable {

        TurnRecoveryRun NOOP = new TurnRecoveryRun() {
            @Override
            public void close() {
            }

            @Override
            public void complete(Throwable failure) {
            }
        };

        /** Unbinds the run from the calling thread; the durable run stays open. */
        @Override
        void close();

        /** Completes the durable run at most once, whichever thread finishes the attempt. */
        void complete(Throwable failure);
    }
}
