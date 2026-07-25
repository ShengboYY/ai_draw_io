package org.zipp.ai.application.turn;

import java.util.Objects;

/** Single application control seam for status, cancellation, lease and takeover operations. */
public final class DefaultTurnControlFacade implements TurnControlFacade {

    private final TurnStatusQueryPort status;
    private final ExplicitTurnCancellationPort cancellation;
    private final TurnAttemptLeasePort leases;
    private final AttemptDeadlineCancellationPort deadlines;
    private final TurnAttemptTakeoverPort takeovers;
    private final AdmissionBarrier admissionBarrier;
    private final TurnAttemptCancellationSignalPort cancellationSignals;
    private final TurnLifecycleTracePort trace;

    public DefaultTurnControlFacade(
            TurnStatusQueryPort status,
            ExplicitTurnCancellationPort cancellation,
            TurnAttemptLeasePort leases,
            AttemptDeadlineCancellationPort deadlines,
            TurnAttemptTakeoverPort takeovers,
            AdmissionBarrier admissionBarrier
    ) {
        this(status, cancellation, leases, deadlines, takeovers, admissionBarrier,
                (key, outcome) -> { }, NoopTurnLifecycleTracePort.INSTANCE);
    }

    public DefaultTurnControlFacade(
            TurnStatusQueryPort status,
            ExplicitTurnCancellationPort cancellation,
            TurnAttemptLeasePort leases,
            AttemptDeadlineCancellationPort deadlines,
            TurnAttemptTakeoverPort takeovers,
            AdmissionBarrier admissionBarrier,
            TurnAttemptCancellationSignalPort cancellationSignals
    ) {
        this(status, cancellation, leases, deadlines, takeovers, admissionBarrier,
                cancellationSignals, NoopTurnLifecycleTracePort.INSTANCE);
    }

    public DefaultTurnControlFacade(
            TurnStatusQueryPort status,
            ExplicitTurnCancellationPort cancellation,
            TurnAttemptLeasePort leases,
            AttemptDeadlineCancellationPort deadlines,
            TurnAttemptTakeoverPort takeovers,
            AdmissionBarrier admissionBarrier,
            TurnAttemptCancellationSignalPort cancellationSignals,
            TurnLifecycleTracePort trace
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.takeovers = Objects.requireNonNull(takeovers, "takeovers");
        this.admissionBarrier = Objects.requireNonNull(admissionBarrier, "admissionBarrier");
        this.cancellationSignals = Objects.requireNonNull(cancellationSignals, "cancellationSignals");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public TurnStatusQueryOutcome status(AuthenticatedActor actor, TurnStatusQuery query) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(query, "query");
        if (!actor.ownerKey().equals(query.key().ownerKey())) {
            // Keep cross-owner status probes out of the durable control port.
            throw new IllegalStateException("TURN_NOT_FOUND");
        }
        return status.get(actor, query);
    }

    @Override
    public CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        if (!actor.ownerKey().equals(command.key().ownerKey())) {
            // Cancellation is owner-fenced before it can reach a repository adapter.
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.CANCEL, command.key(), null, 0,
                    null, null, "OWNER_MISMATCH", null));
            return new CancelTurnOutcome.Rejected("OWNER_MISMATCH");
        }
        CancelTurnOutcome outcome = cancellation.cancel(actor, command);
        traceCancel(command.key(), outcome);
        if (outcome instanceof CancelTurnOutcome.Cancelled cancelled) {
            try {
                // The database winner is already durable; local delivery is best effort.
                cancellationSignals.signal(command.key(), cancelled.outcome());
            } catch (RuntimeException ignored) {
                // A local runner failure must not turn a persisted cancel into an API failure.
            }
        }
        return outcome;
    }

    @Override
    public TurnAttemptLeasePort.HeartbeatOutcome heartbeat(FencedAttempt attempt) {
        TurnAttemptLeasePort.HeartbeatOutcome outcome = leases.heartbeat(attempt);
        if (outcome instanceof TurnAttemptLeasePort.LeaseRenewed renewed) {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.LEASE_RENEWED,
                    new FencedAttempt(attempt.key(), renewed.lease(), attempt.contextMessageHighWater(),
                            attempt.inputBindingDigest(), attempt.policy()),
                    "RENEWED",
                    TurnStatus.RUNNING));
        }
        return outcome;
    }

    @Override
    public DeadlineCancelOutcome cancelAtDeadline(FencedAttempt attempt, AttemptDeadlineReason reason) {
        DeadlineCancelOutcome outcome = deadlines.cancel(attempt, reason);
        traceDeadlineCancel(attempt, outcome);
        return outcome;
    }

    @Override
    public TurnAttemptTakeoverPort.TakeoverOutcome takeover(AuthenticatedActor actor, TurnKey key) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(key, "key");
        if (!actor.ownerKey().equals(key.ownerKey())) {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.TAKEOVER, key, null, 0,
                    null, null, "OWNER_MISMATCH", null));
            return new TurnAttemptTakeoverPort.Rejected("OWNER_MISMATCH");
        }
        if (!admissionBarrier.tryEnter()) {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.TAKEOVER, key, null, 0,
                    null, null, "TURN_INSTANCE_NOT_READY", null));
            return new TurnAttemptTakeoverPort.Rejected("TURN_INSTANCE_NOT_READY");
        }
        try {
            // Takeover creates a new attempt and must wait for startup/migration admission.
            TurnAttemptTakeoverPort.TakeoverOutcome outcome = takeovers.takeover(key);
            traceTakeover(key, outcome);
            return outcome;
        } finally {
            admissionBarrier.leave();
        }
    }

    private void traceCancel(TurnKey key, CancelTurnOutcome outcome) {
        if (outcome instanceof CancelTurnOutcome.Cancelled cancelled) {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.CANCEL, key, null, 0,
                    null, null, cancelled.outcome().terminalCode(), cancelled.outcome().status()));
        } else if (outcome instanceof CancelTurnOutcome.AlreadyTerminal terminal) {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.CANCEL, key, null, 0,
                    null, null, "ALREADY_TERMINAL", terminal.outcome().status()));
        } else if (outcome instanceof CancelTurnOutcome.TerminalUnavailable unavailable) {
            traceStatus(TurnLifecycleTraceType.CANCEL, unavailable.status(), unavailable.code());
        } else if (outcome instanceof CancelTurnOutcome.FenceLost lost) {
            traceStatus(TurnLifecycleTraceType.CANCEL, lost.status(), "FENCE_LOST");
        } else {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.CANCEL, key, null, 0,
                    null, null, ((CancelTurnOutcome.Rejected) outcome).code(), null));
        }
    }

    private void traceDeadlineCancel(FencedAttempt attempt, DeadlineCancelOutcome outcome) {
        if (outcome instanceof DeadlineCancelOutcome.Cancelled cancelled) {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.CANCEL, attempt,
                    cancelled.outcome().terminalCode(), cancelled.outcome().status()));
        } else if (outcome instanceof DeadlineCancelOutcome.AlreadyTerminal terminal) {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.CANCEL, attempt, "ALREADY_TERMINAL", terminal.outcome().status()));
        } else if (outcome instanceof DeadlineCancelOutcome.TerminalUnavailable unavailable) {
            traceStatus(TurnLifecycleTraceType.CANCEL, unavailable.status(), unavailable.code());
        } else if (outcome instanceof DeadlineCancelOutcome.FenceLost lost) {
            traceStatus(TurnLifecycleTraceType.CANCEL, lost.status(), "FENCE_LOST");
        } else {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.CANCEL, attempt,
                    ((DeadlineCancelOutcome.TransientFailure) outcome).code(), null));
        }
    }

    private void traceTakeover(TurnKey key, TurnAttemptTakeoverPort.TakeoverOutcome outcome) {
        if (outcome instanceof TurnAttemptTakeoverPort.Claimed claimed) {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.TAKEOVER, claimed.attempt(), "CLAIMED", TurnStatus.RUNNING));
        } else if (outcome instanceof TurnAttemptTakeoverPort.AlreadyTerminal terminal) {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.TAKEOVER, key, null, 0,
                    null, null, "ALREADY_TERMINAL", terminal.outcome().status()));
        } else if (outcome instanceof TurnAttemptTakeoverPort.TerminalUnavailable unavailable) {
            traceStatus(TurnLifecycleTraceType.TAKEOVER, unavailable.status(), unavailable.code());
        } else if (outcome instanceof TurnAttemptTakeoverPort.LeaseActive active) {
            traceStatus(TurnLifecycleTraceType.TAKEOVER, active.status(), "LEASE_ACTIVE");
        } else {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.TAKEOVER, key, null, 0,
                    null, null, ((TurnAttemptTakeoverPort.Rejected) outcome).code(), null));
        }
    }

    private void traceStatus(TurnLifecycleTraceType type, TurnStatusView status, String code) {
        trace.recordSafely(TurnLifecycleTraceEvent.of(
                type, status.key(), status.attemptId(), status.attemptEpoch(),
                null, null, code, status.status()));
    }
}
