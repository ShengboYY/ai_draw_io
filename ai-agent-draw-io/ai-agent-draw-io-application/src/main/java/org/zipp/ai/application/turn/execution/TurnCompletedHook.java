package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;

/** Runs only after a turn's terminal COMPLETED record is durable. */
@FunctionalInterface
public interface TurnCompletedHook {

    TurnCompletedHook NOOP = (attempt, command) -> { };

    void afterCompleted(FencedAttempt attempt, UserTurnCommand command);
}
