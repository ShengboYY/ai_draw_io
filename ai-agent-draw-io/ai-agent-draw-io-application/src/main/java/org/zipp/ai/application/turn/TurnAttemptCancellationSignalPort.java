package org.zipp.ai.application.turn;

/** Sends a durable explicit-cancel winner to the current in-process attempt, if present. */
public interface TurnAttemptCancellationSignalPort {

    void signal(TurnKey key, PersistedTurnOutcome outcome);
}
