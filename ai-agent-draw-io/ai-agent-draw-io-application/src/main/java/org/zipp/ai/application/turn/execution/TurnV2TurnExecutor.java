package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Published isolated handoff: only an accepted claim can enter V2 execution. */
public interface TurnV2TurnExecutor {

    TurnAttemptCompletion execute(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events
    );

    /** Compatibility extension used by the runner to stop nested asynchronous work. */
    default TurnAttemptCompletion execute(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        return execute(accepted, command, events);
    }

    /** Closes the current attempt's local write window before lease-loss detach. */
    void disableWritesAndDrain(FencedAttempt attempt);
}
