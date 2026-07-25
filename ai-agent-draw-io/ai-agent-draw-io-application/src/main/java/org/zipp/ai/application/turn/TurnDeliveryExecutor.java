package org.zipp.ai.application.turn;

/**
 * Transport-neutral execution boundary shared by synchronous and streaming adapters.
 *
 * <p>The event sink is the only delivery-specific input. It must not select a different
 * business executor.</p>
 */
public interface TurnDeliveryExecutor {

    TurnSubmission execute(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    );
}
