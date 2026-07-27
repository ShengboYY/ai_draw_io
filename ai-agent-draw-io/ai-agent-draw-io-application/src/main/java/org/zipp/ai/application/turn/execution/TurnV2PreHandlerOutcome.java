package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Duration;

/** Typed result of the claim-to-route seam; no handler has run when this result is returned. */
public sealed interface TurnV2PreHandlerOutcome
        permits TurnV2PreHandlerOutcome.Ready,
        TurnV2PreHandlerOutcome.Terminal,
        TurnV2PreHandlerOutcome.AlreadyTerminal,
        TurnV2PreHandlerOutcome.FenceLost,
        TurnV2PreHandlerOutcome.Unavailable {

    record Ready(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            TurnRouteDecision decision,
            TurnDecisionCheckpoint checkpoint
    ) implements TurnV2PreHandlerOutcome {
        public Ready {
            if (attempt == null || context == null || readSet == null
                    || decision == null || checkpoint == null) {
                throw new IllegalArgumentException("prepared V2 route values must not be null");
            }
            if (!checkpoint.contextReadSetDigest().equals(readSet.digest())) {
                throw new IllegalArgumentException("prepared route context digest must match read set");
            }
            if (!checkpoint.inputBindingDigest().equals(attempt.inputBindingDigest())) {
                throw new IllegalArgumentException("prepared route input digest must match attempt");
            }
            if (readSet.messageHighWater() != attempt.contextMessageHighWater()) {
                throw new IllegalArgumentException("prepared route high-water must match attempt");
            }
            if (!decisionContextDigest(decision).equals(readSet.digest())) {
                throw new IllegalArgumentException("prepared decision context digest must match read set");
            }
            if (!decisionInputDigest(decision).equals(attempt.inputBindingDigest())) {
                throw new IllegalArgumentException("prepared decision input digest must match attempt");
            }
        }

        private static String decisionContextDigest(TurnRouteDecision decision) {
            if (decision instanceof TurnRouteDecision.Plain value) {
                return value.value().contextReadSetDigest();
            }
            if (decision instanceof TurnRouteDecision.Response value) {
                return value.value().contextReadSetDigest();
            }
            if (decision instanceof TurnRouteDecision.SourcePlanning value) {
                return value.value().contextReadSetDigest();
            }
            if (decision instanceof TurnRouteDecision.Clarification value) {
                return value.value().contextReadSetDigest();
            }
            if (decision instanceof TurnRouteDecision.Unsupported value) {
                return value.value().contextReadSetDigest();
            }
            return ((TurnRouteDecision.Unavailable) decision).value().contextReadSetDigest();
        }

        private static String decisionInputDigest(TurnRouteDecision decision) {
            if (decision instanceof TurnRouteDecision.Plain value) {
                return value.value().inputBindingDigest();
            }
            if (decision instanceof TurnRouteDecision.Response value) {
                return value.value().inputBindingDigest();
            }
            if (decision instanceof TurnRouteDecision.SourcePlanning value) {
                return value.value().inputBindingDigest();
            }
            if (decision instanceof TurnRouteDecision.Clarification value) {
                return value.value().inputBindingDigest();
            }
            if (decision instanceof TurnRouteDecision.Unsupported value) {
                return value.value().inputBindingDigest();
            }
            return ((TurnRouteDecision.Unavailable) decision).value().inputBindingDigest();
        }
    }

    record Terminal(String code, String reason) implements TurnV2PreHandlerOutcome {
        public Terminal {
            requiredText(code, "code");
            requiredText(reason, "reason");
        }

        private static void requiredText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    record AlreadyTerminal(PersistedTurnOutcome outcome) implements TurnV2PreHandlerOutcome {
        public AlreadyTerminal {
            if (outcome == null) {
                throw new IllegalArgumentException("already-terminal outcome must not be null");
            }
        }
    }

    record FenceLost(TurnStatusRef status) implements TurnV2PreHandlerOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("V2 pre-handler fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements TurnV2PreHandlerOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid V2 pre-handler unavailable outcome");
            }
        }
    }
}
