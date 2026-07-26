package org.zipp.ai.application.turn.classification;

public record PlainResponsePlanRejected(String code) implements PlainResponsePlanDecision {

    public PlainResponsePlanRejected {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("plain response rejection code must not be blank");
        }
    }
}
