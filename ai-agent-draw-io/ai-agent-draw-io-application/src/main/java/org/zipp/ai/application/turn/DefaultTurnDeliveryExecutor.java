package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnHandle;

import java.util.Objects;
import java.util.function.Supplier;

/** Delegates both sync and NDJSON delivery to the same application facade. */
public final class DefaultTurnDeliveryExecutor implements TurnDeliveryExecutor {

    private final DiagramTurnFacade facade;
    private final Supplier<TurnAttemptExecutionRunner> runnerProvider;

    public DefaultTurnDeliveryExecutor(DiagramTurnFacade facade) {
        this(facade, () -> null);
    }

    /** Starts accepted attempts when the isolated V2 runner is present; legacy graphs stay submit-only. */
    public DefaultTurnDeliveryExecutor(
            DiagramTurnFacade facade,
            TurnAttemptExecutionRunner runner
    ) {
        this(facade, () -> runner);
    }

    /**
     * Resolves the optional runner at execution time so Spring configuration ordering cannot
     * permanently snapshot an unavailable runner while the application context is still starting.
     */
    public DefaultTurnDeliveryExecutor(
            DiagramTurnFacade facade,
            Supplier<TurnAttemptExecutionRunner> runnerProvider
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.runnerProvider = Objects.requireNonNull(runnerProvider, "runnerProvider");
    }

    @Override
    public TurnSubmission execute(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        return executeTracked(actor, command, events).submission();
    }

    @Override
    public TurnDeliveryExecution executeTracked(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        TurnSubmission submission = facade.execute(actor, command, events);
        TurnAttemptExecutionRunner runner = runnerProvider.get();
        TurnHandle handle = null;
        if (runner != null && submission instanceof TurnSubmission.ExecutionAccepted accepted) {
            handle = runner.start(accepted, command, events);
        }
        return new TurnDeliveryExecution(submission, handle);
    }
}
