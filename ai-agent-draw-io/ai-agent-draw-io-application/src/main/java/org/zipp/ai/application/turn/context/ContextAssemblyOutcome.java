package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface ContextAssemblyOutcome
        permits ContextAssemblyOutcome.Ready,
        ContextAssemblyOutcome.Retry,
        ContextAssemblyOutcome.Terminal,
        ContextAssemblyOutcome.FenceLost,
        ContextAssemblyOutcome.Unavailable {

    record Ready(BaseTurnContext context, ContextReadSet readSet)
            implements ContextAssemblyOutcome {
        public Ready {
            if (context == null || readSet == null) {
                throw new IllegalArgumentException("assembled context and read-set are required");
            }
        }
    }

    /** Internal bounded retry signal; it is never emitted after assembly returns. */
    record Retry() implements ContextAssemblyOutcome {
    }

    record Terminal(String code, String reason) implements ContextAssemblyOutcome {
        public Terminal {
            if (code == null || code.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("context terminal values must not be blank");
            }
        }
    }

    record FenceLost(TurnStatusRef status) implements ContextAssemblyOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("context fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements ContextAssemblyOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid context assembly unavailable outcome");
            }
        }
    }
}
