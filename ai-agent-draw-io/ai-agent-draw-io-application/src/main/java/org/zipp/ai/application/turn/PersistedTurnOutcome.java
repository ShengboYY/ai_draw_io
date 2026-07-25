package org.zipp.ai.application.turn;

public record PersistedTurnOutcome(
        TurnStatus status,
        String terminalCode,
        String terminalPayloadType,
        String terminalPayloadRef,
        String terminalPayloadJson
) {

    public PersistedTurnOutcome {
        if (status == null || status == TurnStatus.RUNNING) {
            throw new IllegalArgumentException("terminal outcome must have a terminal status");
        }
        ContractValues.requiredText(terminalCode, "terminalCode");
        ContractValues.requiredText(terminalPayloadType, "terminalPayloadType");
    }
}
