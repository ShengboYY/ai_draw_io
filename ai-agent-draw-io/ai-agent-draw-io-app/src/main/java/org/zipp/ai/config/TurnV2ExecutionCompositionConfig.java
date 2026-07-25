package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainRuntimeRegistry;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;

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

    @Bean
    @ConditionalOnBean({PlainGenerationPort.class, PlainTurnCommitPort.class})
    public PlainRuntimeRegistry plainRuntimeRegistry() {
        return new PlainRuntimeRegistry();
    }

    @Bean
    @ConditionalOnBean({PlainGenerationPort.class, PlainTurnCommitPort.class})
    public PlainExecutionProfile plainExecutionProfile() {
        return PlainExecutionProfile.m2SourceFree();
    }

    @Bean
    @ConditionalOnBean({PlainGenerationPort.class, PlainTurnCommitPort.class})
    public PlainDrawingHandler plainDrawingHandler(
            PlainGenerationPort generation,
            PlainTurnCommitPort commit,
            PlainRuntimeRegistry runtime,
            PlainExecutionProfile profile
    ) {
        return new PlainDrawingHandler(generation, commit, runtime, profile);
    }

    @Bean
    @ConditionalOnBean({TurnV2PreHandlerCoordinator.class, PlainDrawingHandler.class})
    public TurnV2ExecutionCoordinator turnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain
    ) {
        return new DefaultTurnV2ExecutionCoordinator(preHandler, plain);
    }

    @Bean
    @ConditionalOnBean(TurnV2ExecutionCoordinator.class)
    public TurnV2TurnExecutor turnV2TurnExecutor(TurnV2ExecutionCoordinator coordinator) {
        return new DefaultTurnV2TurnExecutor(coordinator);
    }
}
