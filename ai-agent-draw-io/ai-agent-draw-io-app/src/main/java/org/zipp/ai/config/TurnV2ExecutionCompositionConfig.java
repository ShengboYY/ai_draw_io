package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.OptionalEnrichmentFallbackHandler;
import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.DirectGenerationPort;
import org.zipp.ai.application.turn.DirectTurnCommitPort;
import org.zipp.ai.application.turn.DirectTurnHandler;
import org.zipp.ai.application.turn.DirectVisionPort;
import org.zipp.ai.application.turn.EvidenceAnswerGenerationPort;
import org.zipp.ai.application.turn.EvidenceAnswerTurnCommitPort;
import org.zipp.ai.application.turn.EvidenceAnswerTurnHandler;
import org.zipp.ai.application.turn.GroundedGenerationPort;
import org.zipp.ai.application.turn.GroundedTurnCommitPort;
import org.zipp.ai.application.turn.GroundedTurnHandler;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainResponseGenerationPort;
import org.zipp.ai.application.turn.PlainResponseHandler;
import org.zipp.ai.application.turn.PlainRuntimeRegistry;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.ResponseTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnAttemptCancellationRegistry;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnWriteGate;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;
import org.zipp.ai.application.turn.SourceAwarePreparationPort;
import org.zipp.ai.application.turn.SourceExecutionBindingPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.DefaultSourceAwareTurnExecution;
import org.zipp.ai.application.turn.execution.DefaultTurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.DefaultTurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptRecoveryCoordinator;
import org.zipp.ai.application.turn.execution.SourceAwareTurnExecution;
import org.zipp.ai.application.turn.planning.DirectCompositePlanner;
import org.zipp.ai.application.turn.planning.OptionalEnrichmentPlanner;
import org.zipp.ai.application.turn.planning.SourceProbePort;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

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
    @ConditionalOnBean({PlainResponseGenerationPort.class, ResponseTurnCommitPort.class})
    @ConditionalOnMissingBean(PlainExecutionProfile.class)
    public PlainExecutionProfile plainResponseExecutionProfile() {
        // Response-only deployments still use the same source-free execution contract.
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
    @ConditionalOnBean(PlainDrawingHandler.class)
    public OptionalEnrichmentFallbackHandler optionalEnrichmentFallbackHandler(
            PlainDrawingHandler plainDrawing
    ) {
        // Optional discovery may only fall back through the signed source-free Plain branch.
        return new OptionalEnrichmentFallbackHandler(plainDrawing);
    }

    @Bean
    @ConditionalOnBean({PlainResponseGenerationPort.class, ResponseTurnCommitPort.class})
    public PlainResponseHandler plainResponseHandler(
            PlainResponseGenerationPort generation,
            ResponseTurnCommitPort commit,
            PlainExecutionProfile profile,
            TurnWriteGate writeGate
    ) {
        return new PlainResponseHandler(generation, commit, profile, writeGate);
    }

    @Bean
    public DirectCompositePlanner directCompositePlanner() {
        return new DirectCompositePlanner();
    }

    @Bean
    public OptionalEnrichmentPlanner optionalEnrichmentPlanner() {
        return new OptionalEnrichmentPlanner();
    }

    @Bean
    @ConditionalOnBean({SourceProbePort.class, SourceAwarePreparationPort.class,
            SourceExecutionBindingPort.class})
    public SourceAwareTurnExecution sourceAwareTurnExecution(
            SourceProbePort probe,
            DirectCompositePlanner directPlanner,
            OptionalEnrichmentPlanner enrichmentPlanner,
            SourceAwarePreparationPort preparation,
            SourceExecutionBindingPort sourceBinding,
            ObjectProvider<DirectTurnHandler> direct,
            ObjectProvider<GroundedTurnHandler> grounded,
            ObjectProvider<EvidenceAnswerTurnHandler> evidenceAnswer,
            ObjectProvider<OptionalEnrichmentFallbackHandler> optionalFallback
    ) {
        return new DefaultSourceAwareTurnExecution(
                probe,
                directPlanner,
                enrichmentPlanner,
                preparation,
                sourceBinding,
                java.util.Optional.ofNullable(direct.getIfAvailable()),
                java.util.Optional.ofNullable(grounded.getIfAvailable()),
                java.util.Optional.ofNullable(evidenceAnswer.getIfAvailable()),
                java.util.Optional.ofNullable(optionalFallback.getIfAvailable()));
    }

    @Bean
    @ConditionalOnBean({DirectVisionPort.class, DirectGenerationPort.class, DirectTurnCommitPort.class})
    public DirectTurnHandler directTurnHandler(
            DirectVisionPort vision,
            DirectGenerationPort generation,
            DirectTurnCommitPort commit,
            TurnWriteGate writeGate
    ) {
        return new DirectTurnHandler(vision, generation, commit, writeGate);
    }

    @Bean
    @ConditionalOnBean({GroundedGenerationPort.class, GroundedTurnCommitPort.class})
    public GroundedTurnHandler groundedTurnHandler(
            GroundedGenerationPort generation,
            GroundedTurnCommitPort commit,
            TurnWriteGate writeGate
    ) {
        return new GroundedTurnHandler(generation, commit, writeGate);
    }

    @Bean
    @ConditionalOnBean({EvidenceAnswerGenerationPort.class, EvidenceAnswerTurnCommitPort.class})
    public EvidenceAnswerTurnHandler evidenceAnswerTurnHandler(
            EvidenceAnswerGenerationPort generation,
            EvidenceAnswerTurnCommitPort commit,
            TurnWriteGate writeGate
    ) {
        return new EvidenceAnswerTurnHandler(generation, commit, writeGate);
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
            TurnAttemptExecutionStatePort executionState,
            ObjectProvider<PlainResponseHandler> response,
            ObjectProvider<SourceAwareTurnExecution> sourceAware
    ) {
        return new DefaultTurnV2ExecutionCoordinator(
                preHandler, plain, response.getIfAvailable(), sourceAware.getIfAvailable(), executionState);
    }

    @Bean
    @ConditionalOnBean({
            TurnV2PreHandlerCoordinator.class,
            PlainResponseHandler.class,
            TerminalOnlyTurnCommitPort.class
    })
    @ConditionalOnMissingBean(TurnV2ExecutionCoordinator.class)
    public TurnV2ExecutionCoordinator turnV2ResponseOnlyExecutionCoordinator(
            TurnV2PreHandlerCoordinator preHandler,
            PlainResponseHandler response,
            TurnAttemptExecutionStatePort executionState,
            ObjectProvider<SourceAwareTurnExecution> sourceAware
    ) {
        // Response/review must remain reachable when the Plain drawing model is disabled.
        return new DefaultTurnV2ExecutionCoordinator(
                preHandler, response, sourceAware.getIfAvailable(), executionState);
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

    @Bean(name = "turnAttemptScheduler", destroyMethod = "shutdownNow")
    @ConditionalOnBean(TurnV2TurnExecutor.class)
    public ScheduledExecutorService turnAttemptScheduler() {
        // The scheduler is created only with the isolated V2 executor graph and is closed by Spring.
        // The delegated implementation is not a ThreadPoolExecutor, so it cannot suppress the
        // shared execution-pool bean through ThreadPoolConfig's missing-bean condition.
        return Executors.newSingleThreadScheduledExecutor();
    }

    @Bean
    @ConditionalOnBean(value = {
            TurnV2TurnExecutor.class,
            TurnAttemptLeaseSupervisor.class,
            TurnAttemptCancellationRegistry.class,
            ThreadPoolExecutor.class,
            ScheduledExecutorService.class
    }, name = "threadPoolExecutor")
    public TurnAttemptExecutionRunner turnAttemptExecutionRunner(
            TurnV2TurnExecutor executor,
            TurnAttemptLeaseSupervisor heartbeat,
            @Qualifier("threadPoolExecutor") ThreadPoolExecutor executionExecutor,
            ScheduledExecutorService scheduler,
            TurnAttemptCancellationRegistry cancellationRegistry,
            ObjectProvider<TurnLifecycleTracePort> trace
    ) {
        return new TurnAttemptExecutionRunner(
                executor, heartbeat, executionExecutor, scheduler, cancellationRegistry,
                trace.getIfAvailable(() ->
                        org.zipp.ai.application.turn.NoopTurnLifecycleTracePort.INSTANCE));
    }

    @Bean
    @ConditionalOnBean({
            TurnAttemptExecutionRunner.class,
            TurnAttemptInputRecoveryPort.class,
            TurnControlFacade.class
    })
    public TurnAttemptRecoveryCoordinator turnAttemptRecoveryCoordinator(
            TurnControlFacade control,
            TurnAttemptInputRecoveryPort inputs,
            TurnAttemptExecutionRunner runner,
            ObjectProvider<TurnLifecycleTracePort> trace
    ) {
        return new TurnAttemptRecoveryCoordinator(
                control,
                inputs,
                runner,
                trace.getIfAvailable(() -> org.zipp.ai.application.turn.NoopTurnLifecycleTracePort.INSTANCE));
    }
}
