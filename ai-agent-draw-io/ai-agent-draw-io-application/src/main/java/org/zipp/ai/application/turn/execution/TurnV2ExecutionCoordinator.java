package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.UserTurnCommand;

/** Executes an already-claimed isolated V2 turn after Context and route preparation. */
public interface TurnV2ExecutionCoordinator {

    TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnEventSink events
    );
}
