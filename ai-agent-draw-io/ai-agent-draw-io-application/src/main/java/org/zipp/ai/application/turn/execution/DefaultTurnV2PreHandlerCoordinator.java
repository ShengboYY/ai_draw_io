package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionPreparationOutcome;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.context.ContextPreparationOutcome;

import java.util.Objects;

/**
 * Serializes the server-owned preparation order: pinned Context first, then checkpointed route.
 */
public final class DefaultTurnV2PreHandlerCoordinator implements TurnV2PreHandlerCoordinator {

    private final ContextAssemblyCoordinator contextAssembly;
    private final TurnDecisionCoordinator decisions;
    private final TurnAttemptExecutionStatePort executionState;

    public DefaultTurnV2PreHandlerCoordinator(
            ContextAssemblyCoordinator contextAssembly,
            TurnDecisionCoordinator decisions
    ) {
        this(contextAssembly, decisions, ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active());
    }

    public DefaultTurnV2PreHandlerCoordinator(
            ContextAssemblyCoordinator contextAssembly,
            TurnDecisionCoordinator decisions,
            TurnAttemptExecutionStatePort executionState
    ) {
        this.contextAssembly = Objects.requireNonNull(contextAssembly, "contextAssembly");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.executionState = Objects.requireNonNull(executionState, "executionState");
    }

    @Override
    public TurnV2PreHandlerOutcome prepare(FencedAttempt attempt, UserTurnCommand command) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");

        TurnV2PreHandlerOutcome state = stateOutcome(attempt);
        if (state != null) {
            return state;
        }
        ContextPreparationOutcome context = contextAssembly.prepareBeforeRouter(attempt, command);
        if (context instanceof ContextPreparationOutcome.Ready ready) {
            state = stateOutcome(attempt);
            if (state != null) {
                return state;
            }
            // The decision coordinator receives the exact winner selected by Context assembly.
            return mapDecision(attempt, command, ready);
        }
        if (context instanceof ContextPreparationOutcome.Terminal terminal) {
            return new TurnV2PreHandlerOutcome.Terminal(terminal.code(), terminal.reason());
        }
        if (context instanceof ContextPreparationOutcome.FenceLost lost) {
            return new TurnV2PreHandlerOutcome.FenceLost(lost.status());
        }
        ContextPreparationOutcome.Unavailable unavailable =
                (ContextPreparationOutcome.Unavailable) context;
        return new TurnV2PreHandlerOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private TurnV2PreHandlerOutcome mapDecision(
            FencedAttempt attempt,
            UserTurnCommand command,
            ContextPreparationOutcome.Ready context
    ) {
        TurnDecisionPreparationOutcome decision = decisions.preparePinned(
                attempt, command, context.context(), context.readSet());
        TurnV2PreHandlerOutcome state = stateOutcome(attempt);
        if (state != null) {
            return state;
        }
        if (decision instanceof TurnDecisionPreparationOutcome.Ready ready) {
            return new TurnV2PreHandlerOutcome.Ready(
                    attempt, context.context(), context.readSet(), ready.decision(), ready.checkpoint());
        }
        if (decision instanceof TurnDecisionPreparationOutcome.FenceLost lost) {
            return new TurnV2PreHandlerOutcome.FenceLost(lost.status());
        }
        TurnDecisionPreparationOutcome.Unavailable unavailable =
                (TurnDecisionPreparationOutcome.Unavailable) decision;
        return new TurnV2PreHandlerOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
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
}
