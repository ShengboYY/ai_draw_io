package org.zipp.ai.application.turn;

public interface ExplicitTurnCancellationPort {

    CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command);
}
