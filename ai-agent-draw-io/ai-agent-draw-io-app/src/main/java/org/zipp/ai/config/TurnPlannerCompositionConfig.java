package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.checkpoint.DefaultTurnDecisionCoordinator;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointQueryPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.checkpoint.TurnRouteDecisionCodec;
import org.zipp.ai.application.turn.classification.PlainDrawPlanFactory;
import org.zipp.ai.application.turn.classification.TurnClassificationService;
import org.zipp.ai.application.turn.context.RestrictedSourceDemandInputFactory;
import org.zipp.ai.application.turn.context.SemanticRouterContextProjector;
import org.zipp.ai.application.turn.NoopTurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;
import org.zipp.ai.application.turn.planning.DefaultPrePlanner;
import org.zipp.ai.application.turn.planning.DefaultTurnRouteComputer;
import org.zipp.ai.application.turn.planning.DefaultTurnRouteDispatcher;
import org.zipp.ai.application.turn.planning.TurnRouteComputer;
import org.zipp.ai.application.turn.planning.TurnRouteDispatcher;

/** Composes the checkpointed pre-probe planner graph without assigning production V2 turns. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({
        SemanticRouterContextProjector.class,
        RestrictedSourceDemandInputFactory.class,
        TurnClassificationService.class,
        TurnDecisionCheckpointQueryPort.class,
        TurnDecisionCheckpointCommitPort.class,
        TurnRouteDecisionCodec.class
})
public class TurnPlannerCompositionConfig {

    @Bean
    public PlainDrawPlanFactory plainDrawPlanFactory() {
        return new PlainDrawPlanFactory();
    }

    @Bean
    public DefaultPrePlanner prePlanner(PlainDrawPlanFactory plainPlans) {
        return new DefaultPrePlanner(plainPlans);
    }

    @Bean
    public TurnRouteDispatcher turnRouteDispatcher() {
        return new DefaultTurnRouteDispatcher();
    }

    @Bean
    public TurnRouteComputer turnRouteComputer(
            SemanticRouterContextProjector routerProjector,
            RestrictedSourceDemandInputFactory demandInputFactory,
            TurnClassificationService classification,
            DefaultPrePlanner prePlanner,
            TurnRouteDispatcher dispatcher
    ) {
        return new DefaultTurnRouteComputer(
                routerProjector, demandInputFactory, classification, prePlanner, dispatcher);
    }

    @Bean
    public TurnDecisionCoordinator turnDecisionCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit,
            TurnRouteComputer routeComputer,
            TurnRouteDecisionCodec codec,
            ObjectProvider<TurnLifecycleTracePort> trace
    ) {
        return new DefaultTurnDecisionCoordinator(
                query,
                commit,
                routeComputer,
                codec,
                trace.getIfAvailable(() -> NoopTurnLifecycleTracePort.INSTANCE));
    }
}
