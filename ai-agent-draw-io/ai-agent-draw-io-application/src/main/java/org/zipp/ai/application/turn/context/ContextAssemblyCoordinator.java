package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;

/** Owns the bounded candidate-read, pin/load, and exact-materialization loop before Router. */
public interface ContextAssemblyCoordinator extends BaseTurnContextAssembler {

    ContextPreparationOutcome prepareBeforeRouter(
            FencedAttempt attempt,
            UserTurnCommand command
    );
}
