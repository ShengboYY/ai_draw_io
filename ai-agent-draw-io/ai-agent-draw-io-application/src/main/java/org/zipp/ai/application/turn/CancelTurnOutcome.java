package org.zipp.ai.application.turn;

public sealed interface CancelTurnOutcome
        permits CancelTurnOutcome.Cancelled,
        CancelTurnOutcome.AlreadyTerminal,
        CancelTurnOutcome.TerminalUnavailable,
        CancelTurnOutcome.FenceLost,
        CancelTurnOutcome.Rejected {

    record Cancelled(PersistedTurnOutcome outcome) implements CancelTurnOutcome {
        public Cancelled {
            if (outcome == null) {
                throw new IllegalArgumentException("cancelled outcome must not be null");
            }
        }
    }

    record AlreadyTerminal(PersistedTurnOutcome outcome) implements CancelTurnOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code) implements CancelTurnOutcome {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record FenceLost(TurnStatusView status) implements CancelTurnOutcome {
    }

    record Rejected(String code) implements CancelTurnOutcome {

        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
