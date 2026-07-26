package org.zipp.ai.application.turn.planning;

public sealed interface TurnRouteDecision
        permits TurnRouteDecision.Plain,
        TurnRouteDecision.Response,
        TurnRouteDecision.SourcePlanning,
        TurnRouteDecision.Clarification,
        TurnRouteDecision.Unsupported,
        TurnRouteDecision.Unavailable {

    record Plain(PrePlanOutcome.SourceFreeReady value) implements TurnRouteDecision {
    }

    record Response(PrePlanOutcome.SourceFreeResponseReady value) implements TurnRouteDecision {
    }

    record SourcePlanning(PrePlanOutcome.SourcePlanningRequired value) implements TurnRouteDecision {
    }

    record Clarification(PrePlanOutcome.NeedsClarification value) implements TurnRouteDecision {
    }

    record Unsupported(PrePlanOutcome.Unsupported value) implements TurnRouteDecision {
    }

    record Unavailable(PrePlanOutcome.Unavailable value) implements TurnRouteDecision {
    }
}
