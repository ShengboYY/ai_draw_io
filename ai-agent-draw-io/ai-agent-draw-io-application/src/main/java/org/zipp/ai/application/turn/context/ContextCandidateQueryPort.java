package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;

/** Reads current context versions for the one allowed Missing-read-set path. */
public interface ContextCandidateQueryPort {

    ContextCandidateLoadOutcome loadCandidate(FencedAttempt attempt, UserTurnCommand command);
}
