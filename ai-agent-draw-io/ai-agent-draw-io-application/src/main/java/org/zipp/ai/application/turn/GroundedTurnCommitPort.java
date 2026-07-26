package org.zipp.ai.application.turn;

/** Strong Grounded consistency seam. */
@FunctionalInterface
public interface GroundedTurnCommitPort {

    FencedCommitOutcome commit(GroundedTurnCommit command);
}
