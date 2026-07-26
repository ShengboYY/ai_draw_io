package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.classification.TurnClassificationOutcome;
import org.zipp.ai.application.turn.classification.TurnClassificationReady;
import org.zipp.ai.application.turn.classification.TurnClassificationService;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.RestrictedSourceDemandInputFactory;
import org.zipp.ai.application.turn.context.SemanticRouterContextProjector;

import java.time.Duration;
import java.util.Objects;

/**
 * Wires the isolated semantic graph up to Pre-Planner. It intentionally has no Probe,
 * Snapshot, Evidence, or source repository dependency.
 */
public final class DefaultTurnRouteComputer implements TurnRouteComputer {

    private final SemanticRouterContextProjector routerProjector;
    private final RestrictedSourceDemandInputFactory demandInputFactory;
    private final TurnClassificationService classification;
    private final DefaultPrePlanner prePlanner;
    private final TurnRouteDispatcher dispatcher;

    public DefaultTurnRouteComputer(
            SemanticRouterContextProjector routerProjector,
            RestrictedSourceDemandInputFactory demandInputFactory,
            TurnClassificationService classification,
            DefaultPrePlanner prePlanner,
            TurnRouteDispatcher dispatcher
    ) {
        this.routerProjector = Objects.requireNonNull(routerProjector, "routerProjector");
        this.demandInputFactory = Objects.requireNonNull(demandInputFactory, "demandInputFactory");
        this.classification = Objects.requireNonNull(classification, "classification");
        this.prePlanner = Objects.requireNonNull(prePlanner, "prePlanner");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public TurnRouteComputationOutcome compute(
            FencedAttempt attempt,
            UserTurnCommand command,
            BaseTurnContext context,
            ContextReadSet readSet
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(readSet, "readSet");
        if (readSet.messageHighWater() != attempt.contextMessageHighWater()
                || !attempt.inputBindingDigest().equals(TurnInputBindingDigestCalculator.current(command))
                || !context.request().turnId().equals(command.turnId())
                || !context.request().instruction().value().equals(command.content())) {
            return unavailable(TurnFailureCode.STALE_ATTEMPT.name());
        }

        var projectedRouter = routerProjector.forRouter(context);
        var projectedDemand = demandInputFactory.create(context);
        // Both model calls use one server-owned TurnKey/read-set pair, while retaining separate
        // projection digests so a fresh session cannot reuse the other model's input.
        var routerInput = projectedRouter.withModelInputBinding(ModelInputBinding.bound(
                attempt.key(), readSet.digest(), projectedRouter.inputDigest()));
        var demandInput = projectedDemand.withModelInputBinding(ModelInputBinding.bound(
                attempt.key(), readSet.digest(), projectedDemand.inputDigest()));
        TurnClassificationOutcome classified = classification.classify(routerInput, demandInput);
        if (classified instanceof TurnClassificationReady ready) {
            PrePlanOutcome prePlan = prePlanner.plan(
                    ready.classification(), readSet.digest(), attempt.inputBindingDigest());
            return new TurnRouteComputationOutcome.Ready(dispatcher.dispatch(prePlan));
        }
        return unavailable(((org.zipp.ai.application.turn.classification.TurnClassificationUnavailable)
                classified).code());
    }

    private TurnRouteComputationOutcome.Unavailable unavailable(String code) {
        return new TurnRouteComputationOutcome.Unavailable(code, Duration.ZERO);
    }
}
