package org.zipp.ai.application.turn;

/** Strong Evidence Answer consistency seam. */
@FunctionalInterface
public interface EvidenceAnswerTurnCommitPort {

    FencedCommitOutcome commit(EvidenceAnswerTurnCommit command);
}
