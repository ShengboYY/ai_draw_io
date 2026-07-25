package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.TurnEventSink;
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

    public DefaultTurnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain
    ) {
        this.preHandler = Objects.requireNonNull(preHandler, "preHandler");
        this.plain = Objects.requireNonNull(plain, "plain");
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
        if (ready.decision() instanceof TurnRouteDecision.Plain) {
            return new TurnV2ExecutionOutcome.Committed(plain.execute(ready, events));
        }
        return new TurnV2ExecutionOutcome.NotDispatched(
                ready.decision(), nonPlainCode(ready.decision()));
    }

    private String nonPlainCode(TurnRouteDecision decision) {
        if (decision instanceof TurnRouteDecision.SourcePlanning) {
            return "SOURCE_AWARE_HANDLER_NOT_AVAILABLE";
        }
        if (decision instanceof TurnRouteDecision.Clarification) {
            return "CLARIFICATION_HANDLER_NOT_AVAILABLE";
        }
        if (decision instanceof TurnRouteDecision.Unsupported) {
            return "UNSUPPORTED_ROUTE_NOT_EXECUTABLE";
        }
        return "UNAVAILABLE_ROUTE_NOT_EXECUTABLE";
    }
}
