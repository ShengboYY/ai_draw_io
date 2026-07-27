package org.zipp.ai.application.turn;

public interface TerminalOnlyTurnCommitPort {

    FencedCommitOutcome commit(TerminalOnlyTurnCommit command);
}
