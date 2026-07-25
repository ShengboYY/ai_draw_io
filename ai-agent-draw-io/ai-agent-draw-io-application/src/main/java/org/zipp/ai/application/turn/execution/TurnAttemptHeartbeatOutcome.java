package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

/** Result of one fenced heartbeat, kept separate from model execution completion. */
public sealed interface TurnAttemptHeartbeatOutcome
        permits TurnAttemptHeartbeatOutcome.Renewed,
        TurnAttemptHeartbeatOutcome.OwnershipLost,
        TurnAttemptHeartbeatOutcome.PersistedTerminal,
        TurnAttemptHeartbeatOutcome.Unavailable,
        TurnAttemptHeartbeatOutcome.Retry {

    record Renewed(FencedAttempt attempt, LeaseTimingAnchor timing) implements TurnAttemptHeartbeatOutcome {

        public Renewed {
            if (attempt == null || timing == null || !attempt.lease().equals(timing.lease())) {
                throw new IllegalArgumentException("renewed heartbeat values must agree");
            }
        }
    }

    record OwnershipLost(TurnStatusRef status) implements TurnAttemptHeartbeatOutcome {

        public OwnershipLost {
            if (status == null) {
                throw new IllegalArgumentException("ownership-lost status must not be null");
            }
        }
    }

    record PersistedTerminal(PersistedTurnOutcome outcome) implements TurnAttemptHeartbeatOutcome {

        public PersistedTerminal {
            if (outcome == null) {
                throw new IllegalArgumentException("persisted terminal must not be null");
            }
        }
    }

    record Unavailable(TurnStatusRef status, String code, Duration retryAfter)
            implements TurnAttemptHeartbeatOutcome {

        public Unavailable {
            if (status == null || code == null || code.isBlank()
                    || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid heartbeat unavailable outcome");
            }
        }
    }

    record Retry(Duration retryAfter) implements TurnAttemptHeartbeatOutcome {

        public Retry {
            if (retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid heartbeat retry outcome");
            }
        }
    }
}
