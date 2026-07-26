package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.util.Objects;

/**
 * Isolated V2 execution seam. It is deliberately Plain-only until the source-aware handlers
 * acquire their own typed plans, snapshots, and strong commit ports.
 */
public final class DefaultTurnV2ExecutionCoordinator implements TurnV2ExecutionCoordinator {

    private final TurnV2PreHandlerCoordinator preHandler;
    private final PlainDrawingHandler plain;
    private final TurnAttemptExecutionStatePort executionState;

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain
    ) {
        this(preHandler, plain, ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active());
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain,
            TurnAttemptExecutionStatePort executionState
    ) {
        this.preHandler = Objects.requireNonNull(preHandler, "preHandler");
        this.plain = Objects.requireNonNull(plain, "plain");
        this.executionState = Objects.requireNonNull(executionState, "executionState");
    }

    @Override
    public TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");

        TurnV2PreHandlerOutcome prepared = preHandler.prepare(attempt, command);
        if (!(prepared instanceof TurnV2PreHandlerOutcome.Ready ready)) {
            return new TurnV2ExecutionOutcome.PreparationBlocked(prepared);
        }
        TurnV2PreHandlerOutcome state = stateOutcome(attempt);
        if (state != null) {
            return new TurnV2ExecutionOutcome.PreparationBlocked(state);
        }
        if (ready.decision() instanceof TurnRouteDecision.Plain) {
            return new TurnV2ExecutionOutcome.Committed(plain.execute(ready, events));
        }
        return new TurnV2ExecutionOutcome.NotDispatched(
                ready.decision(), nonPlainCode(ready.decision()));
    }

    private TurnV2PreHandlerOutcome stateOutcome(FencedAttempt attempt) {
        TurnAttemptExecutionStatePort.StateOutcome state = executionState.check(attempt);
        if (state instanceof TurnAttemptExecutionStatePort.StateOutcome.Active) {
            return null;
        }
        if (state instanceof TurnAttemptExecutionStatePort.StateOutcome.AlreadyTerminal terminal) {
            return new TurnV2PreHandlerOutcome.AlreadyTerminal(terminal.outcome());
        }
        if (state instanceof TurnAttemptExecutionStatePort.StateOutcome.FenceLost lost) {
            return new TurnV2PreHandlerOutcome.FenceLost(
                    new TurnStatusRef(lost.status().key()));
        }
        TurnAttemptExecutionStatePort.StateOutcome.Unavailable unavailable =
                (TurnAttemptExecutionStatePort.StateOutcome.Unavailable) state;
        return new TurnV2PreHandlerOutcome.Unavailable(
                new TurnStatusRef(unavailable.status().key()),
                TurnFailureCode.TERMINAL_UNAVAILABLE,
                unavailable.retryAfter());
    }

    private String nonPlainCode(TurnRouteDecision decision) {
        if (decision instanceof TurnRouteDecision.SourcePlanning) {
            return "SOURCE_AWARE_HANDLER_NOT_AVAILABLE";
        }
        if (decision instanceof TurnRouteDecision.Clarification) {
            // M1 has no durable clarification authority; reject explicitly until M4 adds it.
            return "CLARIFICATION_DEFERRED";
        }
        if (decision instanceof TurnRouteDecision.Unsupported) {
            return "UNSUPPORTED_ROUTE_NOT_EXECUTABLE";
        }
        return "UNAVAILABLE_ROUTE_NOT_EXECUTABLE";
    }
}
