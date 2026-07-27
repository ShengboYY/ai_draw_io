package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.PlainResponsePlan;

public record PlainResponsePlanReady(PlainResponsePlan plan) implements PlainResponsePlanDecision {

    public PlainResponsePlanReady {
        if (plan == null) {
            throw new IllegalArgumentException("plain response plan must not be null");
        }
    }
}
