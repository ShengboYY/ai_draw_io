package org.zipp.ai.application.turn;

public sealed interface TurnStatusQueryOutcome
        permits TurnStatusQueryOutcome.Available,
        TurnStatusQueryOutcome.TerminalUnavailable,
        TurnStatusQueryOutcome.NotFound {

    record Available(TurnStatusView status) implements TurnStatusQueryOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code) implements TurnStatusQueryOutcome {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    /** Hides owner and existence details while keeping status lookup a typed outcome. */
    record NotFound(TurnKey key) implements TurnStatusQueryOutcome {

        public NotFound {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
        }
    }
}
