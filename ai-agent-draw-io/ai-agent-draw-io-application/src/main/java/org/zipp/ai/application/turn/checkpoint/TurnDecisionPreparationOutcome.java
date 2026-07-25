package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Duration;

/** Public result after a route decision has been loaded or first-writer pinned. */
public sealed interface TurnDecisionPreparationOutcome
        permits TurnDecisionPreparationOutcome.Ready,
        TurnDecisionPreparationOutcome.FenceLost,
        TurnDecisionPreparationOutcome.Unavailable {

    record Ready(TurnRouteDecision decision, TurnDecisionCheckpoint checkpoint)
            implements TurnDecisionPreparationOutcome {
        public Ready {
            if (decision == null || checkpoint == null) {
                throw new IllegalArgumentException("prepared decision values must not be null");
            }
        }
    }

    record FenceLost(TurnStatusRef status) implements TurnDecisionPreparationOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("decision fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements TurnDecisionPreparationOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid decision unavailable outcome");
            }
        }
    }
}
