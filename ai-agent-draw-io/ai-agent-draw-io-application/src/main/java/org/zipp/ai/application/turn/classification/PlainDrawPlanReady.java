package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.PlainDrawPlan;

public record PlainDrawPlanReady(PlainDrawPlan plan) implements PlainDrawPlanDecision {

    public PlainDrawPlanReady {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
    }
}
