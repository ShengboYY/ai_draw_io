package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;

import java.util.Objects;

/** Delegates both sync and NDJSON delivery to the same application facade. */
public final class DefaultTurnDeliveryExecutor implements TurnDeliveryExecutor {

    private final DiagramTurnFacade facade;
    private final TurnAttemptExecutionRunner runner;

    public DefaultTurnDeliveryExecutor(DiagramTurnFacade facade) {
        this(facade, null);
    }

    /** Starts accepted attempts when the isolated V2 runner is present; legacy graphs stay submit-only. */
    public DefaultTurnDeliveryExecutor(
            DiagramTurnFacade facade,
            TurnAttemptExecutionRunner runner
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.runner = runner;
    }

    @Override
    public TurnSubmission execute(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        TurnSubmission submission = facade.execute(actor, command, events);
        if (runner != null && submission instanceof TurnSubmission.ExecutionAccepted accepted) {
            runner.start(accepted, command, events);
        }
        return submission;
    }
}
