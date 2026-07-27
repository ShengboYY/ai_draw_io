package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.NoopTurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnLifecycleTraceEvent;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnLifecycleTraceType;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnSubmission;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Connects durable takeover, pinned-input recovery, and the local attempt runner. */
public final class TurnAttemptRecoveryCoordinator {

    private final TurnControlFacade control;
    private final TurnAttemptInputRecoveryPort inputs;
    private final TurnAttemptExecutionRunner runner;
    private final LongSupplier monotonicNanos;
    private final TurnLifecycleTracePort trace;
    private final TurnRecoveryTelemetryPort runTelemetry;

    public TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner
    ) {
        this(control, inputs, runner, System::nanoTime, NoopTurnLifecycleTracePort.INSTANCE);
    }

    public TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            TurnLifecycleTracePort trace
    ) {
        this(control, inputs, runner, System::nanoTime, trace);
    }

    public TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            TurnLifecycleTracePort trace,
            TurnRecoveryTelemetryPort runTelemetry
    ) {
        this(control, inputs, runner, System::nanoTime, trace, runTelemetry);
    }

    TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            LongSupplier monotonicNanos
    ) {
        this(control, inputs, runner, monotonicNanos, NoopTurnLifecycleTracePort.INSTANCE);
    }

    TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            LongSupplier monotonicNanos,
            TurnLifecycleTracePort trace
    ) {
        this(control, inputs, runner, monotonicNanos, trace, TurnRecoveryTelemetryPort.NOOP);
    }

    TurnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            LongSupplier monotonicNanos,
            TurnLifecycleTracePort trace,
            TurnRecoveryTelemetryPort runTelemetry
    ) {
        this.control = Objects.requireNonNull(control, "control");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.runTelemetry = Objects.requireNonNull(runTelemetry, "runTelemetry");
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
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.TAKEOVER,
                    attempt,
                    unavailable.code(),
                    null));
            return new TurnAttemptRecoveryOutcome.Unavailable(unavailable.code());
        }
        if (recovered instanceof TurnAttemptInputRecoveryPort.FenceLost) {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.TAKEOVER,
                    attempt,
                    "INPUT_RECOVERY_FENCE_LOST",
                    null));
            return new TurnAttemptRecoveryOutcome.OwnershipLost();
        }

        TurnAttemptInputRecoveryPort.Recovered ready =
                (TurnAttemptInputRecoveryPort.Recovered) recovered;
        long callStartedNanos = monotonicNanos.getAsLong();
        TurnSubmission.ExecutionAccepted accepted = new TurnSubmission.ExecutionAccepted(
                key, attempt, new LeaseTimingAnchor(callStartedNanos, attempt.lease()));
        // The run must be bound before dispatch: the execution pool captures the calling thread's
        // context, and everything the attempt models afterwards attaches to whatever it captured.
        TurnRecoveryTelemetryPort.TurnRecoveryRun run = runTelemetry.open(attempt);
        TurnHandle handle;
        try (TurnRecoveryTelemetryPort.TurnRecoveryRun bound = run) {
            handle = runner.start(accepted, ready.command(), events);
        } catch (RuntimeException failure) {
            run.complete(failure);
            throw failure;
        }
        handle.completion().whenComplete(
                (result, error) -> run.complete(error != null ? error : attemptFailure(result)));
        return new TurnAttemptRecoveryOutcome.Started(handle, attempt);
    }

    /** Only a COMPLETED durable terminal is a successful run; every other end state is a failure. */
    private static Throwable attemptFailure(TurnAttemptCompletion result) {
        if (result instanceof TurnAttemptCompletion.PersistedTerminal terminal) {
            return terminal.outcome().status() == TurnStatus.COMPLETED
                    ? null
                    : new IllegalStateException(terminal.outcome().terminalCode());
        }
        if (result instanceof TurnAttemptCompletion.AttemptSelfAborted aborted) {
            return new IllegalStateException(aborted.code());
        }
        if (result instanceof TurnAttemptCompletion.StatusOnly statusOnly) {
            return new IllegalStateException(statusOnly.code());
        }
        return new IllegalStateException("TURN_ATTEMPT_OWNERSHIP_LOST");
    }
}
