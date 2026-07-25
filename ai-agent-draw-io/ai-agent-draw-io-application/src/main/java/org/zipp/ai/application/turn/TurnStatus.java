package org.zipp.ai.application.turn;

public enum TurnStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED,
    EXPIRED_GONE,
    ORPHANED_RETRYABLE;

    public boolean isTerminal() {
        return this != RUNNING && this != ORPHANED_RETRYABLE;
    }
}
