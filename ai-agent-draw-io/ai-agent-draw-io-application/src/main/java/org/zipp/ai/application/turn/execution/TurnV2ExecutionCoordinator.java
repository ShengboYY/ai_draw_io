package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Executes an already-claimed isolated V2 turn after Context and route preparation. */
public interface TurnV2ExecutionCoordinator {

    TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnEventSink events
    );

    /** Compatibility extension used by the execution runner to propagate cancellation. */
    default TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        return execute(attempt, command, events);
    }
}
