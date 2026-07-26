package org.zipp.ai.application.turn;

import java.util.Optional;

public record TerminalOnlyTurnCommit(
        FencedAttempt attempt,
        TurnStatus terminalStatus,
        String terminalCode,
        String terminalPayloadType,
        String terminalPayloadRef,
        String terminalPayloadJson,
        Optional<DurableClarification> clarification
) {

    public TerminalOnlyTurnCommit {
        if (attempt == null || terminalStatus == null || !terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("invalid terminal commit");
        }
        ContractValues.requiredText(terminalCode, "terminalCode");
        ContractValues.requiredText(terminalPayloadType, "terminalPayloadType");
        clarification = clarification == null ? Optional.empty() : clarification;
        if (clarification.isPresent() && terminalStatus != TurnStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "clarification authority requires a rejected needs-user-input terminal");
        }
    }

    public TerminalOnlyTurnCommit(
            FencedAttempt attempt,
            TurnStatus terminalStatus,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            String terminalPayloadJson
    ) {
        this(
                attempt,
                terminalStatus,
                terminalCode,
                terminalPayloadType,
                terminalPayloadRef,
                terminalPayloadJson,
                Optional.empty());
    }
}
