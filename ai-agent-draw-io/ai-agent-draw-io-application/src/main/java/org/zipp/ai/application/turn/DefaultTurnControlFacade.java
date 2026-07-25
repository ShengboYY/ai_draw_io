package org.zipp.ai.application.turn;

import java.util.Objects;

/** Single application control seam for status, cancellation, lease and takeover operations. */
public final class DefaultTurnControlFacade implements TurnControlFacade {

    private final TurnStatusQueryPort status;
    private final ExplicitTurnCancellationPort cancellation;
    private final TurnAttemptLeasePort leases;
    private final AttemptDeadlineCancellationPort deadlines;
    private final TurnAttemptTakeoverPort takeovers;

    public DefaultTurnControlFacade(
            TurnStatusQueryPort status,
            ExplicitTurnCancellationPort cancellation,
            TurnAttemptLeasePort leases,
            AttemptDeadlineCancellationPort deadlines,
            TurnAttemptTakeoverPort takeovers
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.takeovers = Objects.requireNonNull(takeovers, "takeovers");
    }

    @Override
    public TurnStatusView status(AuthenticatedActor actor, TurnStatusQuery query) {
        return status.get(actor, query);
    }

    @Override
    public CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command) {
        return cancellation.cancel(actor, command);
    }

    @Override
    public TurnAttemptLeasePort.HeartbeatOutcome heartbeat(FencedAttempt attempt) {
        return leases.heartbeat(attempt);
    }

    @Override
    public DeadlineCancelOutcome cancelAtDeadline(FencedAttempt attempt, AttemptDeadlineReason reason) {
        return deadlines.cancel(attempt, reason);
    }

    @Override
    public TurnAttemptTakeoverPort.TakeoverOutcome takeover(AuthenticatedActor actor, TurnKey key) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(key, "key");
        if (!actor.ownerKey().equals(key.ownerKey())) {
            return new TurnAttemptTakeoverPort.Rejected("OWNER_MISMATCH");
        }
        return takeovers.takeover(key);
    }
}
