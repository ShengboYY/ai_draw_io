package org.zipp.ai.application.turn;

public sealed interface CancelTurnOutcome
        permits CancelTurnOutcome.Cancelled,
        CancelTurnOutcome.AlreadyTerminal,
        CancelTurnOutcome.FenceLost,
        CancelTurnOutcome.Rejected {

    record Cancelled(TurnStatusView status) implements CancelTurnOutcome {
    }

    record AlreadyTerminal(PersistedTurnOutcome outcome) implements CancelTurnOutcome {
    }

    record FenceLost(TurnStatusView status) implements CancelTurnOutcome {
    }

    record Rejected(String code) implements CancelTurnOutcome {

        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
