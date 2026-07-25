package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.FencedAttempt;

public interface TurnDecisionCheckpointCommitPort {

    TurnDecisionCheckpointOutcome pinFirst(
            FencedAttempt attempt,
            ProposedTurnDecisionCheckpoint proposal
    );
}
