package org.zipp.ai.application.turn;

public interface TurnStatusQueryPort {

    TurnStatusView get(AuthenticatedActor actor, TurnStatusQuery query);
}
