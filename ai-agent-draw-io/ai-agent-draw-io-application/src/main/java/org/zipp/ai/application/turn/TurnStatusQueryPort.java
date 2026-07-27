package org.zipp.ai.application.turn;

public interface TurnStatusQueryPort {

    TurnStatusQueryOutcome get(AuthenticatedActor actor, TurnStatusQuery query);
}
