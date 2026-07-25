package org.zipp.ai.application.turn;

public sealed interface TurnStatusQueryOutcome
        permits TurnStatusQueryOutcome.Available,
        TurnStatusQueryOutcome.TerminalUnavailable {

    record Available(TurnStatusView status) implements TurnStatusQueryOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code) implements TurnStatusQueryOutcome {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }
}
