package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;

/** Computes the closed pre-probe route from one exact Base Context and read-set. */
public interface TurnRouteComputer {

    TurnRouteComputationOutcome compute(
            FencedAttempt attempt,
            UserTurnCommand command,
            BaseTurnContext context,
            ContextReadSet readSet
    );
}
