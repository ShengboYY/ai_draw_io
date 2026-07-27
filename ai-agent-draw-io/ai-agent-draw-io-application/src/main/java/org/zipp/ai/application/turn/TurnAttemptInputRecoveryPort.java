package org.zipp.ai.application.turn;

/** Reconstructs the first pinned user input for a freshly claimed attempt. */
public interface TurnAttemptInputRecoveryPort {

    RecoveryOutcome recover(FencedAttempt attempt);

    sealed interface RecoveryOutcome
            permits Recovered, Unavailable, FenceLost {
    }

    record Recovered(UserTurnCommand command) implements RecoveryOutcome {
        public Recovered {
            if (command == null) {
                throw new IllegalArgumentException("command must not be null");
            }
        }
    }

    record Unavailable(String code) implements RecoveryOutcome {
        public Unavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record FenceLost() implements RecoveryOutcome {
    }
}
