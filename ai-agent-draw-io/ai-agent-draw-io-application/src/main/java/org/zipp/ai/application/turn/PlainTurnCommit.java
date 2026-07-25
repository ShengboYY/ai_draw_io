package org.zipp.ai.application.turn;

/** Placeholder seam for the later source-free strong commit; it is not wired in M1. */
public record PlainTurnCommit(FencedAttempt attempt, String payloadRef) {

    public PlainTurnCommit {
        if (attempt == null) {
            throw new IllegalArgumentException("attempt must not be null");
        }
        ContractValues.requiredText(payloadRef, "payloadRef");
    }
}
