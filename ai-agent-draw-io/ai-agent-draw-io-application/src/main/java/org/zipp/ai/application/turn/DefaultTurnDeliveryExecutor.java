package org.zipp.ai.application.turn;

import java.util.Objects;

/** Delegates both sync and NDJSON delivery to the same application facade. */
public final class DefaultTurnDeliveryExecutor implements TurnDeliveryExecutor {

    private final DiagramTurnFacade facade;

    public DefaultTurnDeliveryExecutor(DiagramTurnFacade facade) {
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    public TurnSubmission execute(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        return facade.execute(actor, command, events);
    }
}
