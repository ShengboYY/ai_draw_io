package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;

/**
 * Pre-handler application boundary for an already claimed V2 attempt.
 *
 * <p>This port deliberately starts after admission and stops before any path-specific handler or
 * source probe. A new user turn must still enter through the public facade.</p>
 */
public interface TurnV2PreHandlerCoordinator {

    TurnV2PreHandlerOutcome prepare(FencedAttempt attempt, UserTurnCommand command);
}
