package org.zipp.ai.application.turn;

import java.time.Duration;

public interface TurnAttemptLeasePort {

    HeartbeatOutcome heartbeat(FencedAttempt attempt);

    sealed interface HeartbeatOutcome
            permits LeaseRenewed, LeaseFenceLost, LeaseAlreadyTerminal,
            LeaseTerminalUnavailable, LeaseTransientFailure {
    }

    record LeaseRenewed(AttemptLease lease) implements HeartbeatOutcome {
    }

    record LeaseFenceLost(TurnStatusView status) implements HeartbeatOutcome {
    }

    record LeaseAlreadyTerminal(PersistedTurnOutcome outcome) implements HeartbeatOutcome {
    }

    record LeaseTerminalUnavailable(
            TurnStatusView status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements HeartbeatOutcome {
    }

    record LeaseTransientFailure(Duration retryAfter) implements HeartbeatOutcome {
    }
}
