package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnStatusView;

/** Typed result of taking over a durable attempt and starting local execution. */
public sealed interface TurnAttemptRecoveryOutcome
        permits TurnAttemptRecoveryOutcome.Started,
        TurnAttemptRecoveryOutcome.TerminalReplay,
        TurnAttemptRecoveryOutcome.AlreadyRunning,
        TurnAttemptRecoveryOutcome.Unavailable,
        TurnAttemptRecoveryOutcome.OwnershipLost,
        TurnAttemptRecoveryOutcome.Rejected {

    record Started(TurnHandle handle, FencedAttempt attempt)
            implements TurnAttemptRecoveryOutcome {
        public Started {
            if (handle == null || attempt == null) {
                throw new IllegalArgumentException("handle and attempt must not be null");
            }
        }
    }

    record TerminalReplay(PersistedTurnOutcome outcome) implements TurnAttemptRecoveryOutcome {
        public TerminalReplay {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
        }
    }

    record AlreadyRunning(TurnStatusView status) implements TurnAttemptRecoveryOutcome {
        public AlreadyRunning {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
        }
    }

    record Unavailable(String code) implements TurnAttemptRecoveryOutcome {
        public Unavailable {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
        }
    }

    record OwnershipLost() implements TurnAttemptRecoveryOutcome {
    }

    record Rejected(String code) implements TurnAttemptRecoveryOutcome {
        public Rejected {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
        }
    }
}
