package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommit;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnWriteGate;
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
    public TurnAttemptCompletion execute(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");
        try {
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
            return mapExecutionOutcome(accepted, outcome);
        } catch (RuntimeException ignored) {
            // Execution errors detach this attempt; they must not become a product terminal.
            return selfAborted(accepted, "TURN_EXECUTION_FAILED");
        }
    }

    @Override
    public void disableWritesAndDrain(FencedAttempt attempt) {
        writeGate.disableAndDrain(attempt);
    }

    private TurnAttemptCompletion terminalCommit(
            TurnSubmission.ExecutionAccepted accepted,
            TurnStatus status,
            String code,
            String payloadType
    ) {
        var permit = writeGate.tryEnter(accepted.attempt());
        if (permit.isEmpty()) {
            return selfAborted(accepted, "TURN_WRITE_GATE_DISABLED");
        }
        FencedCommitOutcome outcome;
        try (TurnWriteGate.Permit ignored = permit.get()) {
            outcome = terminalCommit.commit(new TerminalOnlyTurnCommit(
                    accepted.attempt(), status, code, payloadType, null, "{}"));
        }
        return mapCommitOutcome(accepted, outcome);
    }

    private TurnAttemptCompletion mapExecutionOutcome(
            TurnSubmission.ExecutionAccepted accepted,
            TurnV2ExecutionOutcome outcome
    ) {
        if (outcome instanceof TurnV2ExecutionOutcome.Committed committed) {
            return mapCommitOutcome(accepted, committed.outcome());
        }
        if (outcome instanceof TurnV2ExecutionOutcome.PreparationBlocked blocked) {
            if (blocked.outcome() instanceof TurnV2PreHandlerOutcome.FenceLost lost) {
                return new TurnAttemptCompletion.AttemptOwnershipLost(lost.status());
            }
            if (blocked.outcome() instanceof TurnV2PreHandlerOutcome.Unavailable unavailable) {
                return new TurnAttemptCompletion.StatusOnly(
                        unavailable.status(), unavailable.code().name());
            }
            TurnV2PreHandlerOutcome.Terminal terminal =
                    (TurnV2PreHandlerOutcome.Terminal) blocked.outcome();
            return selfAborted(accepted, terminal.code());
        }
        TurnV2ExecutionOutcome.NotDispatched notDispatched =
                (TurnV2ExecutionOutcome.NotDispatched) outcome;
        return selfAborted(accepted, notDispatched.code());
    }

    private TurnAttemptCompletion mapCommitOutcome(
            TurnSubmission.ExecutionAccepted accepted,
            FencedCommitOutcome outcome
    ) {
        if (outcome instanceof FencedCommitOutcome.Committed committed) {
            return new TurnAttemptCompletion.PersistedTerminal(committed.outcome());
        }
        if (outcome instanceof FencedCommitOutcome.AlreadyTerminal terminal) {
            return new TurnAttemptCompletion.PersistedTerminal(terminal.outcome());
        }
        if (outcome instanceof FencedCommitOutcome.FenceLost lost) {
            return new TurnAttemptCompletion.AttemptOwnershipLost(
                    new TurnStatusRef(lost.status().key()));
        }
        if (outcome instanceof FencedCommitOutcome.TerminalUnavailable unavailable) {
            return new TurnAttemptCompletion.StatusOnly(
                    new TurnStatusRef(unavailable.status().key()),
                    unavailable.code());
        }
        return selfAborted(accepted, ((FencedCommitOutcome.Rejected) outcome).code());
    }

    private TurnAttemptCompletion selfAborted(
            TurnSubmission.ExecutionAccepted accepted,
            String code
    ) {
        return new TurnAttemptCompletion.AttemptSelfAborted(
                new TurnStatusRef(accepted.key()), code);
    }
}
