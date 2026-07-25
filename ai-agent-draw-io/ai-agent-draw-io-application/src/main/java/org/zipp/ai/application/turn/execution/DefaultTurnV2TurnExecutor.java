package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommit;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnWriteGate;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.util.Objects;

/** Bridges the durable claim result to the isolated route coordinator without changing transport. */
public final class DefaultTurnV2TurnExecutor implements TurnV2TurnExecutor {

    private final TurnV2ExecutionCoordinator coordinator;
    private final TerminalOnlyTurnCommitPort terminalCommit;
    private final TurnWriteGate writeGate;

    public DefaultTurnV2TurnExecutor(
            TurnV2ExecutionCoordinator coordinator,
            TerminalOnlyTurnCommitPort terminalCommit
    ) {
        this(coordinator, terminalCommit, new AttemptWriteGate());
    }

    public DefaultTurnV2TurnExecutor(
            TurnV2ExecutionCoordinator coordinator,
            TerminalOnlyTurnCommitPort terminalCommit,
            TurnWriteGate writeGate
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.terminalCommit = Objects.requireNonNull(terminalCommit, "terminalCommit");
        this.writeGate = Objects.requireNonNull(writeGate, "writeGate");
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
        TurnV2ExecutionOutcome outcome = coordinator.execute(accepted.attempt(), command, events);
        if (outcome instanceof TurnV2ExecutionOutcome.PreparationBlocked blocked
                && blocked.outcome() instanceof TurnV2PreHandlerOutcome.Terminal terminal) {
            return terminalCommit(
                    accepted,
                    TurnStatus.FAILED,
                    terminal.code(),
                    "preparation");
        }
        if (outcome instanceof TurnV2ExecutionOutcome.NotDispatched notDispatched
                && notDispatched.decision() instanceof TurnRouteDecision.Unsupported unsupported) {
            return terminalCommit(
                    accepted,
                    TurnStatus.REJECTED,
                    unsupported.value().code(),
                    "rejection");
        }
        return outcome;
    }

    @Override
    public void disableWritesAndDrain(FencedAttempt attempt) {
        writeGate.disableAndDrain(attempt);
    }

    private TurnV2ExecutionOutcome terminalCommit(
            TurnSubmission.ExecutionAccepted accepted,
            TurnStatus status,
            String code,
            String payloadType
    ) {
        var permit = writeGate.tryEnter(accepted.attempt());
        if (permit.isEmpty()) {
            return new TurnV2ExecutionOutcome.Committed(
                    new FencedCommitOutcome.Rejected("TURN_WRITE_GATE_DISABLED"));
        }
        FencedCommitOutcome outcome;
        try (TurnWriteGate.Permit ignored = permit.get()) {
            outcome = terminalCommit.commit(new TerminalOnlyTurnCommit(
                    accepted.attempt(), status, code, payloadType, null, "{}"));
        }
        return new TurnV2ExecutionOutcome.Committed(outcome);
    }
}
