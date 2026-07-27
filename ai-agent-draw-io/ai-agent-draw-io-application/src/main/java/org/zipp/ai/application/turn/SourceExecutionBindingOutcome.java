package org.zipp.ai.application.turn;

/** Result of pinning the freeze output before source-aware generation starts. */
public sealed interface SourceExecutionBindingOutcome
        permits SourceExecutionBindingOutcome.Pinned,
        SourceExecutionBindingOutcome.AlreadyPinned,
        SourceExecutionBindingOutcome.FenceLost,
        SourceExecutionBindingOutcome.TerminalUnavailable,
        SourceExecutionBindingOutcome.Rejected {

    record Pinned() implements SourceExecutionBindingOutcome {
    }

    record AlreadyPinned() implements SourceExecutionBindingOutcome {
    }

    record FenceLost(TurnStatusView status) implements SourceExecutionBindingOutcome {
    }

    record TerminalUnavailable(TurnStatusView status, String code)
            implements SourceExecutionBindingOutcome {
        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record Rejected(String code) implements SourceExecutionBindingOutcome {
        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
