package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;

public interface ContextReadSetCommitPort {

    ContextReadSetOutcome pinFirst(FencedAttempt attempt, ProposedContextReadSet proposal);
}
