package org.zipp.ai.application.turn;

public sealed interface FencedCommitOutcome
        permits FencedCommitOutcome.Committed,
        FencedCommitOutcome.FenceLost,
        FencedCommitOutcome.AlreadyTerminal,
        FencedCommitOutcome.TerminalUnavailable,
        FencedCommitOutcome.Rejected {

    record Committed(PersistedTurnOutcome outcome) implements FencedCommitOutcome {
    }

    record FenceLost(TurnStatusView status) implements FencedCommitOutcome {
    }

    record AlreadyTerminal(PersistedTurnOutcome outcome) implements FencedCommitOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code) implements FencedCommitOutcome {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record Rejected(String code) implements FencedCommitOutcome {

        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
