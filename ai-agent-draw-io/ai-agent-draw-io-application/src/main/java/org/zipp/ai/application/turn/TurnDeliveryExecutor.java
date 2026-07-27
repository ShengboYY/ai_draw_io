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

    /**
     * Returns the local execution handle when this delivery call starts a new attempt. Existing
     * implementations remain source-compatible and may return a submission-only receipt.
     */
    default TurnDeliveryExecution executeTracked(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        return new TurnDeliveryExecution(execute(actor, command, events), null);
    }
}
