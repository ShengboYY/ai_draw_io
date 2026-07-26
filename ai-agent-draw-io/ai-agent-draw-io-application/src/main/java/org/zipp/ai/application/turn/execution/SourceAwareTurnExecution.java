package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Executes a source-planning route after the source-free pre-handler has pinned Context. */
@FunctionalInterface
public interface SourceAwareTurnExecution {

    TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnV2PreHandlerOutcome.Ready prepared,
            TurnEventSink events
    );

    /** Executes with the runner-owned cancellation signal for asynchronous source preparation. */
    default TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnV2PreHandlerOutcome.Ready prepared,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        return execute(attempt, command, prepared, events);
    }
}
