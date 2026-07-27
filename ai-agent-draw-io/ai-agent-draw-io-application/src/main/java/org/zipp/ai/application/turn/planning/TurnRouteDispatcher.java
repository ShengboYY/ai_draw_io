package org.zipp.ai.application.turn.planning;

public interface TurnRouteDispatcher {

    TurnRouteDecision dispatch(PrePlanOutcome outcome);
}
