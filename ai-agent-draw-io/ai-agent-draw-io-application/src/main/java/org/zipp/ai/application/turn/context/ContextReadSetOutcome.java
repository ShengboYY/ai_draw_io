package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface ContextReadSetOutcome
        permits ContextReadSetOutcome.Pinned,
        ContextReadSetOutcome.Retry,
        ContextReadSetOutcome.FenceLost,
        ContextReadSetOutcome.Unavailable,
        ContextReadSetOutcome.Revoked {

    record Pinned(ContextReadSet value) implements ContextReadSetOutcome {
        public Pinned {
            if (value == null) {
                throw new IllegalArgumentException("pinned context read-set must not be null");
            }
        }
    }

    record Retry() implements ContextReadSetOutcome {
    }

    record FenceLost(TurnStatusRef status) implements ContextReadSetOutcome {
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
    ) implements ContextReadSetOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid context read-set unavailable outcome");
            }
        }
    }

    record Revoked(String reason) implements ContextReadSetOutcome {
        public Revoked {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("revocation reason must not be blank");
            }
        }
    }
}
