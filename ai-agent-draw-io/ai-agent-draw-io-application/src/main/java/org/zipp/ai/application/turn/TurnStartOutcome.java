package org.zipp.ai.application.turn;

public sealed interface TurnStartOutcome
        permits TurnStartOutcome.Claimed,
        TurnStartOutcome.AlreadyRunning,
        TurnStartOutcome.TerminalReplay,
        TurnStartOutcome.TerminalUnavailable,
        TurnStartOutcome.Rejected {

    record Claimed(FencedAttempt attempt, long requestMessageId) implements TurnStartOutcome {

        public Claimed {
            if (attempt == null || requestMessageId <= 0) {
                throw new IllegalArgumentException("invalid claimed turn");
            }
        }
    }

    record AlreadyRunning(TurnStatusView status) implements TurnStartOutcome {
    }

    record TerminalReplay(PersistedTurnOutcome outcome) implements TurnStartOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code) implements TurnStartOutcome {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record Rejected(String code) implements TurnStartOutcome {

        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
