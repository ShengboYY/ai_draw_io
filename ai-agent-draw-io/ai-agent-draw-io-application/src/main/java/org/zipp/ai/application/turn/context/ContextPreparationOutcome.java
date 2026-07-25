package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

/** Public pre-Router result; retry signals are consumed inside the coordinator. */
public sealed interface ContextPreparationOutcome
        permits ContextPreparationOutcome.Ready,
        ContextPreparationOutcome.Terminal,
        ContextPreparationOutcome.FenceLost,
        ContextPreparationOutcome.Unavailable {

    record Ready(BaseTurnContext context) implements ContextPreparationOutcome {
        public Ready {
            if (context == null) {
                throw new IllegalArgumentException("prepared context must not be null");
            }
        }
    }

    record Terminal(String code, String reason) implements ContextPreparationOutcome {
        public Terminal {
            if (code == null || code.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("context terminal values must not be blank");
            }
        }
    }

    record FenceLost(TurnStatusRef status) implements ContextPreparationOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("prepared context fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements ContextPreparationOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid prepared context unavailable outcome");
            }
        }
    }
}
