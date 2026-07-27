package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.checkpoint.DefaultTurnDecisionCheckpointCoordinator;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointQueryPort;
import org.zipp.ai.application.turn.context.ContextReadSetCommitPort;
import org.zipp.ai.application.turn.context.ContextReadSetQueryPort;
import org.zipp.ai.application.turn.context.DefaultContextReadSetCoordinator;

/** Composes durable load-first pin coordinators without enabling a production V2 route. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "turn-engine.execution.enabled", havingValue = "true")
public class TurnCheckpointCompositionConfig {

    @Bean
    public DefaultContextReadSetCoordinator contextReadSetCoordinator(
            ContextReadSetQueryPort query,
            ContextReadSetCommitPort commit
    ) {
        return new DefaultContextReadSetCoordinator(query, commit);
    }

    @Bean
    public DefaultTurnDecisionCheckpointCoordinator turnDecisionCheckpointCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit
    ) {
        return new DefaultTurnDecisionCheckpointCoordinator(query, commit);
    }
}
