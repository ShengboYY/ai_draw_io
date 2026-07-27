package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface TurnDecisionCheckpointOutcome
        permits TurnDecisionCheckpointOutcome.Pinned,
        TurnDecisionCheckpointOutcome.Retry,
        TurnDecisionCheckpointOutcome.FenceLost,
        TurnDecisionCheckpointOutcome.Unavailable {

    record Pinned(TurnDecisionCheckpoint value) implements TurnDecisionCheckpointOutcome {
        public Pinned {
            if (value == null) {
                throw new IllegalArgumentException("pinned checkpoint must not be null");
            }
        }
    }

    record Retry() implements TurnDecisionCheckpointOutcome {
    }

    record FenceLost(TurnStatusRef status) implements TurnDecisionCheckpointOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements TurnDecisionCheckpointOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid checkpoint unavailable outcome");
            }
        }
    }
}
