package org.zipp.ai.application.turn;

/** Strong Direct consistency seam. */
@FunctionalInterface
public interface DirectTurnCommitPort {

    FencedCommitOutcome commit(DirectTurnCommit command);
}
