package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Connects durable attempt heartbeat outcomes to the local V2 execution fence.
 *
 * <p>The supervisor does not own a scheduler. A runner calls {@link #isDue}
 * using the accepted monotonic anchor and invokes {@link #heartbeat}; this
 * keeps scheduling policy outside the application contract while making every
 * lease-loss path drain local writes before detaching.</p>
 */
public final class TurnAttemptLeaseSupervisor {

    private final TurnAttemptLeasePort leases;
    private final TurnV2TurnExecutor executor;
    private final LongSupplier monotonicNanos;

    public TurnAttemptLeaseSupervisor(
            TurnAttemptLeasePort leases,
            TurnV2TurnExecutor executor
    ) {
        this(leases, executor, System::nanoTime);
    }

    TurnAttemptLeaseSupervisor(
            TurnAttemptLeasePort leases,
            TurnV2TurnExecutor executor,
            LongSupplier monotonicNanos
    ) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    public boolean isDue(LeaseTimingAnchor anchor, long nowNanos) {
        Objects.requireNonNull(anchor, "anchor");
        return nowNanos >= anchor.callStartedNanos()
                && nowNanos - anchor.callStartedNanos()
                >= anchor.lease().renewWithin().toNanos();
    }

    public TurnAttemptHeartbeatOutcome heartbeat(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        long callStartedNanos = monotonicNanos.getAsLong();
        TurnAttemptLeasePort.HeartbeatOutcome outcome = leases.heartbeat(attempt);
        if (outcome instanceof TurnAttemptLeasePort.LeaseRenewed renewed) {
            AttemptLease lease = renewed.lease();
            FencedAttempt next = new FencedAttempt(
                    attempt.key(),
                    lease,
                    attempt.contextMessageHighWater(),
                    attempt.inputBindingDigest(),
                    attempt.policy());
            return new TurnAttemptHeartbeatOutcome.Renewed(
                    next, new LeaseTimingAnchor(callStartedNanos, lease));
        }
        if (outcome instanceof TurnAttemptLeasePort.LeaseFenceLost lost) {
            executor.disableWritesAndDrain(attempt);
            return new TurnAttemptHeartbeatOutcome.OwnershipLost(
                    new TurnStatusRef(lost.status().key()));
        }
        if (outcome instanceof TurnAttemptLeasePort.LeaseAlreadyTerminal terminal) {
            executor.disableWritesAndDrain(attempt);
            PersistedTurnOutcome persisted = terminal.outcome();
            return new TurnAttemptHeartbeatOutcome.PersistedTerminal(persisted);
        }
        if (outcome instanceof TurnAttemptLeasePort.LeaseTerminalUnavailable unavailable) {
            executor.disableWritesAndDrain(attempt);
            return new TurnAttemptHeartbeatOutcome.Unavailable(
                    new TurnStatusRef(unavailable.status().key()),
                    unavailable.code().name(), unavailable.retryAfter());
        }
        TurnAttemptLeasePort.LeaseTransientFailure transientFailure =
                (TurnAttemptLeasePort.LeaseTransientFailure) outcome;
        return new TurnAttemptHeartbeatOutcome.Retry(transientFailure.retryAfter());
    }
}
