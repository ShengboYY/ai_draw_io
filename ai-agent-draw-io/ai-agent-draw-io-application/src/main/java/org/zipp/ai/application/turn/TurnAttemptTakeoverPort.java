package org.zipp.ai.application.turn;

/** Claims an expired or startup-orphaned execution with a fresh fenced epoch. */
public interface TurnAttemptTakeoverPort {

    TakeoverOutcome takeover(TurnKey key);

    sealed interface TakeoverOutcome
            permits Claimed, AlreadyTerminal, LeaseActive, Rejected {
    }

    record Claimed(FencedAttempt attempt) implements TakeoverOutcome {
    }

    record AlreadyTerminal(PersistedTurnOutcome outcome) implements TakeoverOutcome {
    }

    record LeaseActive(TurnStatusView status) implements TakeoverOutcome {
    }

    record Rejected(String code) implements TakeoverOutcome {

        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
