package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.PlainResponseKind;
import org.zipp.ai.application.turn.PlainResponsePlan;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;

/** Creates only source-free answer/review/reply plans from an explicitly resolved no-source demand. */
public final class PlainResponsePlanFactory {

    public PlainResponsePlanDecision create(TurnClassification classification) {
        if (classification == null) {
            return new PlainResponsePlanRejected("CLASSIFICATION_REQUIRED");
        }
        if (!(classification.demandResolution() instanceof ResolvedSourceDemand resolved)
                || !(resolved.decision() instanceof NoSourceDemand)) {
            return new PlainResponsePlanRejected("SOURCE_PLANNING_REQUIRED");
        }

        PlainResponseKind kind = switch (classification.intent().action()) {
            case ANSWER -> classification.intent().outputIntent() == OutputIntent.TEXT
                    ? PlainResponseKind.ANSWER : null;
            case REVIEW -> classification.intent().outputIntent() == OutputIntent.REVIEW
                    ? PlainResponseKind.REVIEW : null;
            case DIRECT_REPLY -> classification.intent().outputIntent() == OutputIntent.TEXT
                    ? PlainResponseKind.DIRECT_REPLY : null;
            default -> null;
        };
        if (kind == null) {
            return new PlainResponsePlanRejected("PLAIN_RESPONSE_ACTION_UNSUPPORTED");
        }
        return new PlainResponsePlanReady(
                new PlainResponsePlan(
                        kind,
                        classification.instruction().value(),
                        classification.intent().targetNeed() == TargetNeed.CANVAS_REQUIRED
                                || classification.intent().targetNeed() == TargetNeed.CANVAS_OPTIONAL));
    }
}
