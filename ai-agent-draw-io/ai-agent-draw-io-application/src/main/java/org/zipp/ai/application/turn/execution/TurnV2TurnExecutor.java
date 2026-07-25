package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

/** Published isolated handoff: only an accepted claim can enter V2 execution. */
public interface TurnV2TurnExecutor {

    TurnV2ExecutionOutcome execute(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events
    );

    /** Closes the current attempt's local write window before lease-loss detach. */
    void disableWritesAndDrain(FencedAttempt attempt);
}
