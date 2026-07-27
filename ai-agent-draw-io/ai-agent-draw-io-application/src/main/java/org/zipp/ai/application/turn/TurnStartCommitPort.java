package org.zipp.ai.application.turn;

/** Strong transaction boundary for claim + unique user message + attachment bindings. */
public interface TurnStartCommitPort {

    TurnStartOutcome start(TurnStartCommand command);
}
