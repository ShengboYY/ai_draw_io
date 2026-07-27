package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.NoopTurnLifecycleTracePort;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainResponseGenerationPort;
import org.zipp.ai.application.turn.PlainResponseHandler;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.ResponseTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnAttemptCancellationRegistry;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.TurnWriteGate;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.TurnAttemptCompletion;
import org.zipp.ai.application.turn.execution.TurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptRecoveryCoordinator;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnV2ExecutionCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnV2ExecutionCompositionConfig.class)
            .withPropertyValues("turn-engine.execution.enabled=true")
            .withBean(ContextAssemblyCoordinator.class, () -> mock(ContextAssemblyCoordinator.class))
            .withBean(TurnDecisionCoordinator.class, () -> mock(TurnDecisionCoordinator.class))
            .withBean(TurnAttemptCancellationRegistry.class, TurnAttemptCancellationRegistry::new)
            .withBean(TurnAttemptInputRecoveryPort.class, () -> mock(TurnAttemptInputRecoveryPort.class))
            .withBean(TurnControlFacade.class, () -> mock(TurnControlFacade.class))
            .withBean(ThreadPoolExecutor.class, () -> mock(ThreadPoolExecutor.class));

    @Test
    void composesThePreHandlerCoordinatorOnlyWhenBothPreparationSeamsExist() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(TurnAttemptExecutionStatePort.class)
                .hasSingleBean(TurnV2PreHandlerCoordinator.class));
    }

    @Test
    void composesPlainExecutionOnlyWhenBothPlainPortsExist() {
        contextRunner
                .withBean(PlainGenerationPort.class, () -> mock(PlainGenerationPort.class))
                .withBean(PlainTurnCommitPort.class, () -> mock(PlainTurnCommitPort.class))
                .withBean(TerminalOnlyTurnCommitPort.class, () -> mock(TerminalOnlyTurnCommitPort.class))
                .withBean(TurnAttemptLeasePort.class, () -> mock(TurnAttemptLeasePort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(PlainDrawingHandler.class)
                        .hasSingleBean(TurnWriteGate.class)
                        .hasSingleBean(TurnV2ExecutionCoordinator.class)
                        .hasSingleBean(TurnV2TurnExecutor.class)
                        .hasSingleBean(TurnAttemptLeaseSupervisor.class)
                        .hasSingleBean(ScheduledExecutorService.class)
                        .hasSingleBean(TurnAttemptExecutionRunner.class)
                        .hasSingleBean(TurnAttemptRecoveryCoordinator.class)
                        .satisfies(appContext -> assertThat(appContext.getBean(ScheduledExecutorService.class))
                                .isNotInstanceOf(ThreadPoolExecutor.class)));
    }

    @Test
    void composesSourceFreeResponseHandlerOnlyWhenBothResponsePortsExist() {
        contextRunner
                .withBean(PlainResponseGenerationPort.class, () -> mock(PlainResponseGenerationPort.class))
                .withBean(ResponseTurnCommitPort.class, () -> mock(ResponseTurnCommitPort.class))
                .withBean(TerminalOnlyTurnCommitPort.class, () -> mock(TerminalOnlyTurnCommitPort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(PlainResponseHandler.class)
                        .hasSingleBean(org.zipp.ai.application.turn.PlainExecutionProfile.class)
                        .hasSingleBean(TurnV2ExecutionCoordinator.class)
                        .hasSingleBean(TurnV2TurnExecutor.class));
    }

    @Test
    void propagatesTheIngressTelemetryContextIntoTheV2Worker() {
        org.zipp.ai.config.TurnV2ExecutionCompositionConfig config =
                new org.zipp.ai.config.TurnV2ExecutionCompositionConfig();
        TurnV2TurnExecutor turnExecutor = mock(TurnV2TurnExecutor.class);
        TurnAttemptLeaseSupervisor heartbeat = mock(TurnAttemptLeaseSupervisor.class);
        ThreadPoolExecutor delegate = mock(ThreadPoolExecutor.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TurnLifecycleTracePort> traces = mock(ObjectProvider.class);
        when(traces.getIfAvailable(any())).thenReturn(NoopTurnLifecycleTracePort.INSTANCE);

        AtomicReference<AgentUsageTelemetryContext.RunContext> observed = new AtomicReference<>();
        TurnSubmission.ExecutionAccepted accepted = acceptedTurn();
        when(turnExecutor.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            observed.set(AgentUsageTelemetryContext.current().orElse(null));
            return new TurnAttemptCompletion.AttemptSelfAborted(
                    new TurnStatusRef(accepted.key()), "TEST_COMPLETE");
        });
        TurnAttemptExecutionRunner runner = config.turnAttemptExecutionRunner(
                turnExecutor,
                heartbeat,
                delegate,
                scheduler,
                new TurnAttemptCancellationRegistry(),
                traces);

        AgentUsageTelemetryContext.RunContext expected =
                new AgentUsageTelemetryContext.RunContext(
                        "aru_test", "request-1", "diagram-1", "owner-1", "agent-1",
                        "chat_stream", "PLATFORM", null, "openai", "unknown", "turn_v2_execution");
        try (AgentUsageTelemetryContext.Scope ignored =
                     AgentUsageTelemetryContext.bind(expected)) {
            runner.start(accepted, mock(org.zipp.ai.application.turn.UserTurnCommand.class), event -> { });
        }

        ArgumentCaptor<Runnable> dispatched = ArgumentCaptor.forClass(Runnable.class);
        verify(delegate).execute(dispatched.capture());
        dispatched.getValue().run();

        assertThat(observed.get()).isSameAs(expected);
        assertThat(AgentUsageTelemetryContext.current()).isEmpty();
    }

    private static TurnSubmission.ExecutionAccepted acceptedTurn() {
        AttemptLease lease = new AttemptLease(
                "attempt-1", 1, Instant.parse("2026-07-27T00:00:30Z"), 30_000);
        FencedAttempt attempt = new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "request-1"),
                lease,
                1,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "policy-hash"));
        return new TurnSubmission.ExecutionAccepted(
                attempt.key(), attempt, new LeaseTimingAnchor(System.nanoTime(), lease));
    }
}
