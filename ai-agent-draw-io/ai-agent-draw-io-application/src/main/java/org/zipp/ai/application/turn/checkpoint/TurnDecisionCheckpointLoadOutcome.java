package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface TurnDecisionCheckpointLoadOutcome
        permits TurnDecisionCheckpointLoadOutcome.Found,
        TurnDecisionCheckpointLoadOutcome.Missing,
        TurnDecisionCheckpointLoadOutcome.FenceLost,
        TurnDecisionCheckpointLoadOutcome.Unavailable {

    record Found(TurnDecisionCheckpoint value) implements TurnDecisionCheckpointLoadOutcome {
        public Found {
            if (value == null) {
                throw new IllegalArgumentException("loaded checkpoint must not be null");
            }
        }
    }

    record Missing() implements TurnDecisionCheckpointLoadOutcome {
    }

    record FenceLost(TurnStatusRef status) implements TurnDecisionCheckpointLoadOutcome {
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
    ) implements TurnDecisionCheckpointLoadOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid checkpoint unavailable outcome");
            }
        }
    }
}
