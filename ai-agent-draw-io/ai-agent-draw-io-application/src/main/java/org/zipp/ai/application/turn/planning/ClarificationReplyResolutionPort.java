package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Duration;

/**
 * Resolves a natural-language clarification proposal against durable hidden authority.
 * Implementations read clarification rows, never Source or Material repositories.
 */
@FunctionalInterface
public interface ClarificationReplyResolutionPort {

    Outcome resolve(
            TurnKey execution,
            ReplyToClarification reply,
            SelectionProposal proposal
    );

    record SelectionProposal(
            String optionId,
            String optionSetDigest
    ) {
        public SelectionProposal {
            if (optionId == null || optionId.isBlank()
                    || optionSetDigest == null || optionSetDigest.isBlank()) {
                throw new IllegalArgumentException("clarification proposal values must not be blank");
            }
        }
    }

    sealed interface Outcome
            permits Verified, Stale, Unavailable {
    }

    record Verified(DirectCandidateFact candidate) implements Outcome {
        public Verified {
            if (candidate == null) {
                throw new IllegalArgumentException("verified candidate must not be null");
            }
        }
    }

    record Stale(String code) implements Outcome {
        public Stale {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("stale clarification code must not be blank");
            }
        }
    }

    record Unavailable(String code, Duration retryAfter) implements Outcome {
        public Unavailable {
            if (code == null || code.isBlank() || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid clarification unavailable outcome");
            }
        }
    }
}
