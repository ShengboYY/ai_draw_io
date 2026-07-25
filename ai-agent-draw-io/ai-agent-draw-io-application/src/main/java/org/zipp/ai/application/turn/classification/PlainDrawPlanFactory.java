package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;

/** The source-free boundary: only a resolved NoSourceDemand can produce a Plain draw plan. */
public final class PlainDrawPlanFactory {

    public PlainDrawPlanDecision create(TurnClassification classification) {
        if (classification == null) {
            return new PlainDrawPlanRejected("CLASSIFICATION_REQUIRED");
        }
        if (!(classification.demandResolution() instanceof ResolvedSourceDemand resolved)
                || !(resolved.decision() instanceof NoSourceDemand)) {
            return new PlainDrawPlanRejected("SOURCE_PLANNING_REQUIRED");
        }

        PlainDrawAction action = switch (classification.intent().action()) {
            case CREATE -> PlainDrawAction.CREATE;
            case EDIT -> PlainDrawAction.EDIT;
            case LAYOUT -> PlainDrawAction.LAYOUT;
            default -> null;
        };
        if (action == null) {
            return new PlainDrawPlanRejected("PLAIN_ACTION_UNSUPPORTED");
        }
        return new PlainDrawPlanReady(new PlainDrawPlan(action, classification.instruction().value()));
    }
}
