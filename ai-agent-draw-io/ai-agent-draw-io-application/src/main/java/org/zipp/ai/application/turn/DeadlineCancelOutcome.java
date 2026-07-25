package org.zipp.ai.application.turn;

public sealed interface DeadlineCancelOutcome
        permits DeadlineCancelOutcome.Cancelled,
        DeadlineCancelOutcome.AlreadyTerminal,
        DeadlineCancelOutcome.TerminalUnavailable,
        DeadlineCancelOutcome.FenceLost,
        DeadlineCancelOutcome.TransientFailure {

    record Cancelled(PersistedTurnOutcome outcome) implements DeadlineCancelOutcome {
        public Cancelled {
            if (outcome == null) {
                throw new IllegalArgumentException("cancelled outcome must not be null");
            }
        }
    }

    record AlreadyTerminal(PersistedTurnOutcome outcome) implements DeadlineCancelOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code) implements DeadlineCancelOutcome {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record FenceLost(TurnStatusView status) implements DeadlineCancelOutcome {
    }

    record TransientFailure(String code) implements DeadlineCancelOutcome {

        public TransientFailure {
            ContractValues.requiredText(code, "code");
        }
    }
}
