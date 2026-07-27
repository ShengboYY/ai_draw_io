package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface ContextReadSetLoadOutcome
        permits ContextReadSetLoadOutcome.Found,
        ContextReadSetLoadOutcome.Missing,
        ContextReadSetLoadOutcome.FenceLost,
        ContextReadSetLoadOutcome.Unavailable,
        ContextReadSetLoadOutcome.Revoked {

    record Found(ContextReadSet value) implements ContextReadSetLoadOutcome {
        public Found {
            if (value == null) {
                throw new IllegalArgumentException("loaded context read-set must not be null");
            }
        }
    }

    record Missing() implements ContextReadSetLoadOutcome {
    }

    record FenceLost(TurnStatusRef status) implements ContextReadSetLoadOutcome {
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
    ) implements ContextReadSetLoadOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid context read-set unavailable outcome");
            }
        }
    }

    record Revoked(String reason) implements ContextReadSetLoadOutcome {
        public Revoked {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("revocation reason must not be blank");
            }
        }
    }
}
