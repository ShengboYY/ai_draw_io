package org.zipp.ai.application.turn.planning;

import java.time.Duration;

/** Typed result for the pre-probe route computation; source I/O is outside this boundary. */
public sealed interface TurnRouteComputationOutcome
        permits TurnRouteComputationOutcome.Ready, TurnRouteComputationOutcome.Unavailable {

    record Ready(TurnRouteDecision decision) implements TurnRouteComputationOutcome {
        public Ready {
            if (decision == null) {
                throw new IllegalArgumentException("computed route decision must not be null");
            }
        }
    }

    record Unavailable(String code, Duration retryAfter) implements TurnRouteComputationOutcome {
        public Unavailable {
            if (code == null || code.isBlank() || retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("invalid route computation unavailable outcome");
            }
        }
    }
}
