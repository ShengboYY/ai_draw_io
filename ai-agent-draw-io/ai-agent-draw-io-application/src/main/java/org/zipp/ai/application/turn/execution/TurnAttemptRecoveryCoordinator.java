package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnSubmission;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Connects durable takeover, pinned-input recovery, and the local attempt runner. */
public final class TurnAttemptRecoveryCoordinator {

    private final TurnControlFacade control;
    private final TurnAttemptInputRecoveryPort inputs;
    private final TurnAttemptExecutionRunner runner;
    private final LongSupplier monotonicNanos;

    public TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner
    ) {
        this(control, inputs, runner, System::nanoTime);
    }

    TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            LongSupplier monotonicNanos
    ) {
        this.control = Objects.requireNonNull(control, "control");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    /** Takes over only after the durable control facade grants a fresh fenced epoch. */
    public TurnAttemptRecoveryOutcome start(
            AuthenticatedActor actor,
            TurnKey key,
            TurnEventSink events
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(events, "events");

        TurnAttemptTakeoverPort.TakeoverOutcome takeover = control.takeover(actor, key);
        if (takeover instanceof TurnAttemptTakeoverPort.AlreadyTerminal terminal) {
            return new TurnAttemptRecoveryOutcome.TerminalReplay(terminal.outcome());
        }
        if (takeover instanceof TurnAttemptTakeoverPort.TerminalUnavailable unavailable) {
            return new TurnAttemptRecoveryOutcome.Unavailable(unavailable.code());
        }
        if (takeover instanceof TurnAttemptTakeoverPort.LeaseActive active) {
            return new TurnAttemptRecoveryOutcome.AlreadyRunning(active.status());
        }
        if (takeover instanceof TurnAttemptTakeoverPort.Rejected rejected) {
            return new TurnAttemptRecoveryOutcome.Rejected(rejected.code());
        }

        FencedAttempt attempt = ((TurnAttemptTakeoverPort.Claimed) takeover).attempt();
        TurnAttemptInputRecoveryPort.RecoveryOutcome recovered = inputs.recover(attempt);
        if (recovered instanceof TurnAttemptInputRecoveryPort.Unavailable unavailable) {
            return new TurnAttemptRecoveryOutcome.Unavailable(unavailable.code());
        }
        if (recovered instanceof TurnAttemptInputRecoveryPort.FenceLost) {
            return new TurnAttemptRecoveryOutcome.OwnershipLost();
        }

        TurnAttemptInputRecoveryPort.Recovered ready =
                (TurnAttemptInputRecoveryPort.Recovered) recovered;
        long callStartedNanos = monotonicNanos.getAsLong();
        TurnSubmission.ExecutionAccepted accepted = new TurnSubmission.ExecutionAccepted(
                key, attempt, new LeaseTimingAnchor(callStartedNanos, attempt.lease()));
        TurnHandle handle = runner.start(accepted, ready.command(), events);
        return new TurnAttemptRecoveryOutcome.Started(handle, attempt);
    }
}
