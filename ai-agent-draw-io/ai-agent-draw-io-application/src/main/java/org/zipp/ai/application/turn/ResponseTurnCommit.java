package org.zipp.ai.application.turn;

/** Placeholder seam for source-aware response commits; it is not wired in M1. */
public record ResponseTurnCommit(FencedAttempt attempt, String payloadRef) {

    public ResponseTurnCommit {
        if (attempt == null) {
            throw new IllegalArgumentException("attempt must not be null");
        }
        ContractValues.requiredText(payloadRef, "payloadRef");
    }
}
