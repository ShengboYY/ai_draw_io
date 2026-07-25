package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.util.Objects;

/** Bridges the durable claim result to the isolated route coordinator without changing transport. */
public final class DefaultTurnV2TurnExecutor implements TurnV2TurnExecutor {

    private final TurnV2ExecutionCoordinator coordinator;

    public DefaultTurnV2TurnExecutor(TurnV2ExecutionCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public TurnV2ExecutionOutcome execute(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");
        return coordinator.execute(accepted.attempt(), command, events);
    }
}
