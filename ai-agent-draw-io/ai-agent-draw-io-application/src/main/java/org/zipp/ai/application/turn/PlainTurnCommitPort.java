package org.zipp.ai.application.turn;

public interface PlainTurnCommitPort {

    FencedCommitOutcome commit(PlainTurnCommit command);
}
