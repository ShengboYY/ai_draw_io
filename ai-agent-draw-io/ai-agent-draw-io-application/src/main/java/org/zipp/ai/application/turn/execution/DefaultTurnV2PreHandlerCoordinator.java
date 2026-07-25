package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
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

    public DefaultTurnV2PreHandlerCoordinator(
            ContextAssemblyCoordinator contextAssembly,
            TurnDecisionCoordinator decisions
    ) {
        this.contextAssembly = Objects.requireNonNull(contextAssembly, "contextAssembly");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Override
    public TurnV2PreHandlerOutcome prepare(FencedAttempt attempt, UserTurnCommand command) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");

        ContextPreparationOutcome context = contextAssembly.prepareBeforeRouter(attempt, command);
        if (context instanceof ContextPreparationOutcome.Ready ready) {
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
}
