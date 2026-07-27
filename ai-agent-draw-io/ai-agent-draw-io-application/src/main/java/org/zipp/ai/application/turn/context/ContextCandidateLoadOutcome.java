package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface ContextCandidateLoadOutcome
        permits ContextCandidateLoadOutcome.Ready,
        ContextCandidateLoadOutcome.Retry,
        ContextCandidateLoadOutcome.FenceLost,
        ContextCandidateLoadOutcome.Unavailable,
        ContextCandidateLoadOutcome.Revoked {

    record Ready(ContextCandidate value) implements ContextCandidateLoadOutcome {
        public Ready {
            if (value == null) {
                throw new IllegalArgumentException("context candidate must not be null");
            }
        }
    }

    record Retry() implements ContextCandidateLoadOutcome {
    }

    record FenceLost(TurnStatusRef status) implements ContextCandidateLoadOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("candidate fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements ContextCandidateLoadOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid context candidate unavailable outcome");
            }
        }
    }

    record Revoked(String reason) implements ContextCandidateLoadOutcome {
        public Revoked {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("candidate revocation reason must not be blank");
            }
        }
    }
}
