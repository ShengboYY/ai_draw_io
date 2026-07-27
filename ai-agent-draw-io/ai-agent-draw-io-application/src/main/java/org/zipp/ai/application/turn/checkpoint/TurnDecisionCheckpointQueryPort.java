package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.FencedAttempt;

public interface TurnDecisionCheckpointQueryPort {

    TurnDecisionCheckpointLoadOutcome loadPinned(FencedAttempt attempt);
}
