package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;

import java.util.concurrent.CompletionStage;

/** Process-local handle for the attempt created by the first successful claim. */
public interface TurnHandle {

    /** Returns the latest fenced lease for this attempt while it remains owned. */
    FencedAttempt attempt();

    CompletionStage<TurnAttemptCompletion> completion();

    /** Detaches the delivery subscriber without cancelling the durable turn. */
    void detach();
}
