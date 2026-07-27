package org.zipp.ai.application.turn;

/** All state needed by one fenced response/review message and terminal transaction. */
public record ResponseTurnCommit(
        FencedAttempt attempt,
        String diagramId,
        String assistantMessage,
        String payloadRef
) {

    public ResponseTurnCommit {
        if (attempt == null) {
            throw new IllegalArgumentException("attempt must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        ContractValues.requiredText(assistantMessage, "assistantMessage");
        ContractValues.requiredText(payloadRef, "payloadRef");
    }
}
