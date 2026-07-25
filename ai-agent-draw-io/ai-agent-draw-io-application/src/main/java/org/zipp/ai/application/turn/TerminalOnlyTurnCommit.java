package org.zipp.ai.application.turn;

public record TerminalOnlyTurnCommit(
        FencedAttempt attempt,
        TurnStatus terminalStatus,
        String terminalCode,
        String terminalPayloadType,
        String terminalPayloadRef,
        String terminalPayloadJson
) {

    public TerminalOnlyTurnCommit {
        if (attempt == null || terminalStatus == null || terminalStatus == TurnStatus.RUNNING) {
            throw new IllegalArgumentException("invalid terminal commit");
        }
        ContractValues.requiredText(terminalCode, "terminalCode");
        ContractValues.requiredText(terminalPayloadType, "terminalPayloadType");
    }
}
