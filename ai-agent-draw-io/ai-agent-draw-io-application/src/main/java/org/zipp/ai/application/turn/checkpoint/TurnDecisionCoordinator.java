package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;

/** Owns load-first, compute-on-missing, and fenced reload of the immutable turn decision. */
public interface TurnDecisionCoordinator {

    TurnDecisionPreparationOutcome preparePinned(
            FencedAttempt attempt,
            UserTurnCommand command,
            BaseTurnContext context,
            ContextReadSet readSet
    );
}
