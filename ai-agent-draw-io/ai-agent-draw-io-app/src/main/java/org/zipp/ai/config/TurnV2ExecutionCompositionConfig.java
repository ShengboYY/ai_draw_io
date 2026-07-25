package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;

/** Composes the isolated claim-to-route seam without changing production engine assignment. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({ContextAssemblyCoordinator.class, TurnDecisionCoordinator.class})
public class TurnV2ExecutionCompositionConfig {

    @Bean
    public TurnV2PreHandlerCoordinator turnV2PreHandlerCoordinator(
            ContextAssemblyCoordinator contextAssembly,
            TurnDecisionCoordinator decisions
    ) {
        return new DefaultTurnV2PreHandlerCoordinator(contextAssembly, decisions);
    }
}
