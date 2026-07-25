package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainRuntimeRegistry;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnWriteGate;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;

/** Composes the isolated claim-to-route seam without changing production engine assignment. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({ContextAssemblyCoordinator.class, TurnDecisionCoordinator.class})
public class TurnV2ExecutionCompositionConfig {

    @Bean
    public TurnWriteGate turnWriteGate() {
        return new AttemptWriteGate();
    }

    @Bean
    @ConditionalOnMissingBean(TurnAttemptExecutionStatePort.class)
    public TurnAttemptExecutionStatePort turnAttemptExecutionStatePort() {
        // Isolated fixtures without a durable adapter remain active by default.
        return ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active();
    }

    @Bean
    public TurnV2PreHandlerCoordinator turnV2PreHandlerCoordinator(
            ContextAssemblyCoordinator contextAssembly,
            TurnDecisionCoordinator decisions,
            TurnAttemptExecutionStatePort executionState
    ) {
        return new DefaultTurnV2PreHandlerCoordinator(contextAssembly, decisions, executionState);
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
            PlainExecutionProfile profile,
            TurnWriteGate writeGate
    ) {
        return new PlainDrawingHandler(generation, commit, runtime, profile, writeGate);
    }

    @Bean
    @ConditionalOnBean({
            TurnV2PreHandlerCoordinator.class,
            PlainDrawingHandler.class,
            TerminalOnlyTurnCommitPort.class
    })
    public TurnV2ExecutionCoordinator turnV2ExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainDrawingHandler plain,
            TurnAttemptExecutionStatePort executionState
    ) {
        return new DefaultTurnV2ExecutionCoordinator(preHandler, plain, executionState);
    }

    @Bean
    @ConditionalOnBean({TurnV2ExecutionCoordinator.class, TerminalOnlyTurnCommitPort.class})
    public TurnV2TurnExecutor turnV2TurnExecutor(
            TurnV2ExecutionCoordinator coordinator,
            TerminalOnlyTurnCommitPort terminalCommit,
            TurnWriteGate writeGate
    ) {
        return new DefaultTurnV2TurnExecutor(coordinator, terminalCommit, writeGate);
    }

    @Bean
    @ConditionalOnBean({TurnV2TurnExecutor.class, TurnAttemptLeasePort.class})
    public TurnAttemptLeaseSupervisor turnAttemptLeaseSupervisor(
            TurnV2TurnExecutor executor,
            TurnAttemptLeasePort leases
    ) {
        return new TurnAttemptLeaseSupervisor(leases, executor);
    }
}
