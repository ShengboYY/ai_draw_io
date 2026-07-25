package org.zipp.ai.application.turn.classification;

public record PlainDrawPlanRejected(String code) implements PlainDrawPlanDecision {

    public PlainDrawPlanRejected {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
