package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.PlainResponseHandler;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.util.Objects;
import java.util.Optional;

/**
 * Isolated V2 execution seam. Source-aware routes are dispatched only when their Probe,
 * preparation, binding, and typed handler capabilities are composed.
 */
public final class DefaultTurnV2ExecutionCoordinator implements TurnV2ExecutionCoordinator {

    private final TurnV2PreHandlerCoordinator preHandler;
    private final Optional<PlainDrawingHandler> plain;
    private final Optional<PlainResponseHandler> response;
    private final Optional<SourceAwareTurnExecution> sourceAware;
    private final TurnAttemptExecutionStatePort executionState;

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain
    ) {
        this(preHandler, plain, null,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active());
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain,
            TurnAttemptExecutionStatePort executionState
    ) {
        this(preHandler, plain, null, executionState);
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain,
            PlainResponseHandler response
    ) {
        this(preHandler, plain, response,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active());
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainResponseHandler response
    ) {
        this(preHandler, response,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active());
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainResponseHandler response,
            TurnAttemptExecutionStatePort executionState
    ) {
        this(preHandler, Optional.empty(), Optional.of(response),
                Optional.empty(), executionState);
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainResponseHandler response,
            SourceAwareTurnExecution sourceAware,
            TurnAttemptExecutionStatePort executionState
    ) {
        this(preHandler, Optional.empty(), Optional.of(response),
                Optional.ofNullable(sourceAware), executionState);
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain,
            PlainResponseHandler response,
            TurnAttemptExecutionStatePort executionState
    ) {
        this(preHandler, Optional.of(plain), Optional.ofNullable(response),
                Optional.empty(), executionState);
    }

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain,
            PlainResponseHandler response,
            SourceAwareTurnExecution sourceAware,
            TurnAttemptExecutionStatePort executionState
    ) {
        this(preHandler, Optional.of(plain), Optional.ofNullable(response),
                Optional.ofNullable(sourceAware), executionState);
    }

    private DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            Optional<PlainDrawingHandler> plain,
            Optional<PlainResponseHandler> response,
            Optional<SourceAwareTurnExecution> sourceAware,
            TurnAttemptExecutionStatePort executionState
    ) {
        this.preHandler = Objects.requireNonNull(preHandler, "preHandler");
        this.plain = Objects.requireNonNull(plain, "plain");
        this.response = Objects.requireNonNull(response, "response");
        this.sourceAware = Objects.requireNonNull(sourceAware, "sourceAware");
        this.executionState = Objects.requireNonNull(executionState, "executionState");
    }

    @Override
    public TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        return execute(attempt, command, events, CancellationSignal.NEVER);
    }

    @Override
    public TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");
        cancellation = cancellation == null ? CancellationSignal.NEVER : cancellation;

        TurnV2PreHandlerOutcome prepared = preHandler.prepare(attempt, command);
        if (!(prepared instanceof TurnV2PreHandlerOutcome.Ready ready)) {
            return new TurnV2ExecutionOutcome.PreparationBlocked(prepared);
        }
        TurnV2PreHandlerOutcome state = stateOutcome(attempt);
        if (state != null) {
            return new TurnV2ExecutionOutcome.PreparationBlocked(state);
        }
        if (ready.decision() instanceof TurnRouteDecision.Plain) {
            if (plain.isEmpty()) {
                return new TurnV2ExecutionOutcome.NotDispatched(
                        ready.decision(), "PLAIN_HANDLER_NOT_AVAILABLE");
            }
            return new TurnV2ExecutionOutcome.Committed(
                    plain.get().execute(ready, events, cancellation));
        }
        if (ready.decision() instanceof TurnRouteDecision.Response responseDecision
                && response.isPresent()) {
            return new TurnV2ExecutionOutcome.Committed(response.get().execute(
                    attempt,
                    ready.context(),
                    ready.readSet(),
                    responseDecision.value().plan(),
                    events,
                    cancellation));
        }
        if (ready.decision() instanceof TurnRouteDecision.SourcePlanning
                && sourceAware.isPresent()) {
            return sourceAware.get().execute(attempt, command, ready, events, cancellation);
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
        if (decision instanceof TurnRouteDecision.Plain) {
            return "PLAIN_HANDLER_NOT_AVAILABLE";
        }
        if (decision instanceof TurnRouteDecision.SourcePlanning) {
            return "SOURCE_AWARE_HANDLER_NOT_AVAILABLE";
        }
        if (decision instanceof TurnRouteDecision.Response) {
            return "PLAIN_RESPONSE_HANDLER_NOT_AVAILABLE";
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
