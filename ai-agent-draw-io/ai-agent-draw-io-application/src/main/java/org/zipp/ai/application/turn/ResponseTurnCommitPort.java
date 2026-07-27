package org.zipp.ai.application.turn;

public interface ResponseTurnCommitPort {

    FencedCommitOutcome commit(ResponseTurnCommit command);
}
