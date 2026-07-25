package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;

public sealed interface ContextMaterializationOutcome
        permits ContextMaterializationOutcome.Ready,
        ContextMaterializationOutcome.Retry,
        ContextMaterializationOutcome.FenceLost,
        ContextMaterializationOutcome.Unavailable,
        ContextMaterializationOutcome.Revoked {

    record Ready(ContextMaterializedSlices value) implements ContextMaterializationOutcome {
        public Ready {
            if (value == null) {
                throw new IllegalArgumentException("materialized context must not be null");
            }
        }
    }

    record Retry() implements ContextMaterializationOutcome {
    }

    record FenceLost(TurnStatusRef status) implements ContextMaterializationOutcome {
        public FenceLost {
            if (status == null) {
                throw new IllegalArgumentException("materialization fence status must not be null");
            }
        }
    }

    record Unavailable(
            TurnStatusRef status,
            TurnFailureCode code,
            Duration retryAfter
    ) implements ContextMaterializationOutcome {
        public Unavailable {
            if (status == null || code == null || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid context materialization unavailable outcome");
            }
        }
    }

    record Revoked(String reason) implements ContextMaterializationOutcome {
        public Revoked {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("materialization revocation reason must not be blank");
            }
        }
    }
}
