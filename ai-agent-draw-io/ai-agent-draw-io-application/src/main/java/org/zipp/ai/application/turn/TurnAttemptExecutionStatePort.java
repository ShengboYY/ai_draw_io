package org.zipp.ai.application.turn;

import java.time.Duration;

/**
 * Reads the current durable state of a claimed attempt before another V2 phase
 * starts. Implementations must use the current attempt fence, not a cached
 * status snapshot.
 */
public interface TurnAttemptExecutionStatePort {

    StateOutcome check(FencedAttempt attempt);

    sealed interface StateOutcome
            permits StateOutcome.Active,
            StateOutcome.AlreadyTerminal,
            StateOutcome.FenceLost,
            StateOutcome.Unavailable {

        record Active() implements StateOutcome {
        }

        record AlreadyTerminal(PersistedTurnOutcome outcome) implements StateOutcome {

            public AlreadyTerminal {
                if (outcome == null) {
                    throw new IllegalArgumentException("terminal outcome must not be null");
                }
            }
        }

        record FenceLost(TurnStatusView status) implements StateOutcome {

            public FenceLost {
                if (status == null) {
                    throw new IllegalArgumentException("fence-lost status must not be null");
                }
            }
        }

        record Unavailable(TurnStatusView status, String code, Duration retryAfter)
                implements StateOutcome {

            public Unavailable {
                if (status == null || code == null || code.isBlank()
                        || retryAfter == null || retryAfter.isNegative()) {
                    throw new IllegalArgumentException("invalid execution state outcome");
                }
            }
        }
    }
}
