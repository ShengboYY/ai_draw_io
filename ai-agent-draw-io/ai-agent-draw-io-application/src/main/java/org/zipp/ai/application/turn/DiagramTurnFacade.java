package org.zipp.ai.application.turn;

public interface DiagramTurnFacade {

    TurnSubmission execute(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    );
}
