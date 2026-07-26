package org.zipp.ai.application.turn.planning;

import java.util.Objects;

/** Pure route translation; it does not probe, freeze, invoke a model, or mutate a diagram. */
public final class DefaultTurnRouteDispatcher implements TurnRouteDispatcher {

    @Override
    public TurnRouteDecision dispatch(PrePlanOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome instanceof PrePlanOutcome.SourceFreeReady ready) {
            return new TurnRouteDecision.Plain(ready);
        }
        if (outcome instanceof PrePlanOutcome.SourceFreeResponseReady response) {
            return new TurnRouteDecision.Response(response);
        }
        if (outcome instanceof PrePlanOutcome.SourcePlanningRequired sourcePlanning) {
            return new TurnRouteDecision.SourcePlanning(sourcePlanning);
        }
        if (outcome instanceof PrePlanOutcome.NeedsClarification clarification) {
            return new TurnRouteDecision.Clarification(clarification);
        }
        if (outcome instanceof PrePlanOutcome.Unsupported unsupported) {
            return new TurnRouteDecision.Unsupported(unsupported);
        }
        return new TurnRouteDecision.Unavailable((PrePlanOutcome.Unavailable) outcome);
    }
}
