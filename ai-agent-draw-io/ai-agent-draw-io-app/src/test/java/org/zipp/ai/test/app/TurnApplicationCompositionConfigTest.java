package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.AdmissionWriteOutcome;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationStatus;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.ExplicitTurnCancellationPort;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.InstanceBootId;
import org.zipp.ai.application.turn.InstanceLockOutcome;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.LegacyRetryExpiryPort;
import org.zipp.ai.application.turn.MigrationModeSwitchCommand;
import org.zipp.ai.application.turn.MigrationModeSwitchOutcome;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.SingleActiveInstanceLock;
import org.zipp.ai.application.turn.StartupOrphanReconciler;
import org.zipp.ai.application.turn.TurnEngineAssignmentCommand;
import org.zipp.ai.application.turn.TurnEngineAssignmentPort;
import org.zipp.ai.application.turn.TurnEngineMigrationControlPort;
import org.zipp.ai.application.turn.TurnEngineMigrationStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQueryPort;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnStartCommand;
import org.zipp.ai.application.turn.TurnStartCommitPort;
import org.zipp.ai.application.turn.TurnStartOutcome;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.execution.TurnAttemptCompletion;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnHandle;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.turn.TurnV2ProductIngressAdapter;
import org.zipp.ai.trigger.http.turn.TurnHttpControlAdapter;
import org.zipp.ai.trigger.http.turn.TurnHttpDeliveryAdapter;
import org.zipp.ai.trigger.http.turn.TurnHttpDeliveryResult;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnApplicationCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnApplicationCompositionConfig.class)
            .withPropertyValues("turn-engine.lifecycle.enabled=true")
            .withBean(SingleActiveInstanceLock.class, FakeInstanceLock::new)
            .withBean(StartupOrphanReconciler.class, FakeOrphanReconciler::new)
            .withBean(TurnEngineMigrationStatePort.class, FakeMigrationState::new)
            .withBean(TurnEngineMigrationControlPort.class, FakeMigrationControl::new)
            .withBean(LegacyRetryExpiryPort.class, FakeExpiry::new)
            .withBean(TurnStatusQueryPort.class, TurnApplicationCompositionConfigTest::fakeStatus)
            .withBean(ExplicitTurnCancellationPort.class, TurnApplicationCompositionConfigTest::fakeCancellation)
            .withBean(TurnAttemptLeasePort.class, TurnApplicationCompositionConfigTest::fakeLease)
            .withBean(AttemptDeadlineCancellationPort.class, TurnApplicationCompositionConfigTest::fakeDeadline)
            .withBean(TurnAttemptTakeoverPort.class, TurnApplicationCompositionConfigTest::fakeTakeover)
            .withBean(TurnEngineAssignmentPort.class, FakeAssignments::new)
            .withBean(ConversationCatalogPort.class, FakeConversationCatalog::new)
            .withBean(TurnStartCommitPort.class, FakeTurnStart::new)
            .withBean(AgentUsageTelemetryService.class, () -> new AgentUsageTelemetryService(
                    new FakeAgentUsageTelemetryStore(), Clock.systemUTC()));

    @Test
    void keepsDurableM1StartupDisabledUntilTheMigrationReleaseIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(org.zipp.ai.config.TurnApplicationCompositionConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            org.zipp.ai.application.turn.TurnAdmissionGate.class);
                    assertThat(context).doesNotHaveBean(org.springframework.boot.ApplicationRunner.class);
                });
    }

    @Test
    void composesFacadeAndOpensAdmissionOnlyAfterStartupRepair() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.DiagramTurnFacade.class);
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnAdmissionGate.class);
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnAttemptCancellationRegistry.class);
            assertThat(context).hasSingleBean(TurnHttpDeliveryAdapter.class);
            assertThat(context).hasSingleBean(TurnHttpControlAdapter.class);
            assertThat(context.getBean(org.zipp.ai.application.turn.TurnAdmissionGate.class).isOpen()).isFalse();

            org.springframework.boot.ApplicationRunner runner = context.getBean(org.springframework.boot.ApplicationRunner.class);
            runner.run(new org.springframework.boot.DefaultApplicationArguments());

            FakeInstanceLock lock = (FakeInstanceLock) context.getBean(SingleActiveInstanceLock.class);
            FakeOrphanReconciler reconciler = (FakeOrphanReconciler) context.getBean(StartupOrphanReconciler.class);
            assertThat(lock.acquired).isTrue();
            assertThat(reconciler.calls).isEqualTo(1);
            assertThat(context.getBean(org.zipp.ai.application.turn.TurnAdmissionGate.class).isOpen()).isTrue();
        });
    }

    @Test
    void doesNotRunMigrationWithoutAnExplicitStartupTarget() {
        contextRunner.run(context -> {
            context.getBean(org.springframework.boot.ApplicationRunner.class)
                    .run(new org.springframework.boot.DefaultApplicationArguments());

            FakeMigrationControl migration = context.getBean(FakeMigrationControl.class);
            assertThat(migration.commands).isEmpty();
        });
    }

    @Test
    void runsExplicitStartupMigrationOnlyAfterAdmissionStartup() {
        contextRunner
                .withPropertyValues("turn-engine.migration.startup-target-mode=ALL_V2")
                .withBean(TurnAttemptExecutionRunner.class, () -> mock(TurnAttemptExecutionRunner.class))
                .run(context -> {
                    context.getBean(org.springframework.boot.ApplicationRunner.class)
                            .run(new org.springframework.boot.DefaultApplicationArguments());

                    FakeMigrationControl migration = context.getBean(FakeMigrationControl.class);
                    assertThat(migration.commands).singleElement()
                            .extracting(MigrationModeSwitchCommand::targetMode)
                            .isEqualTo(TurnEngineMode.ALL_V2);
                });
    }

    @Test
    void canStartTheStableCanaryModeOnlyThroughTheExplicitMigrationHook() {
        contextRunner.withPropertyValues(
                        "turn-engine.migration.startup-target-mode=V2_CANARY",
                        "turn-engine.canary.allowlist=owner-1")
                .withBean(TurnAttemptExecutionRunner.class, () -> mock(TurnAttemptExecutionRunner.class))
                .run(context -> {
                    context.getBean(org.springframework.boot.ApplicationRunner.class)
                            .run(new org.springframework.boot.DefaultApplicationArguments());

                    FakeMigrationControl migration = context.getBean(FakeMigrationControl.class);
                    assertThat(migration.commands).singleElement()
                            .extracting(MigrationModeSwitchCommand::targetMode)
                            .isEqualTo(TurnEngineMode.V2_CANARY);
                    assertThat(context.getBean(
                            org.zipp.ai.application.turn.TurnEngineCohortSelector.class)
                    ).isInstanceOf(org.zipp.ai.application.turn.StableTurnEngineCohortSelector.class);
                });
    }

    @Test
    void rejectsV2StartupBeforeMigrationWhenTheExecutionRunnerIsUnavailable() {
        contextRunner.withPropertyValues("turn-engine.migration.startup-target-mode=V2_CANARY")
                .run(context -> {
                    org.springframework.boot.ApplicationRunner startup =
                            context.getBean(org.springframework.boot.ApplicationRunner.class);

                    org.assertj.core.api.Assertions.assertThatThrownBy(
                                    () -> startup.run(new org.springframework.boot.DefaultApplicationArguments()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("TURN_V2_EXECUTION_NOT_READY");
                    assertThat(context.getBean(FakeMigrationControl.class).commands).isEmpty();
                });
    }

    @Test
    void composesV2DeliveryHandoffWhenAnAttemptRunnerIsAvailable() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            contextRunner.withBean(
                            TurnAttemptExecutionRunner.class,
                            () -> v2Runner(scheduler))
                    .run(context -> {
                        assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnDeliveryExecutor.class);
                    });
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void composesTheProductV2IngressAfterAllDependenciesAreRegistered() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            contextRunner
                    .withUserConfiguration(TurnV2ProductIngressAdapter.class)
                    .withPropertyValues("turn-engine.http.product-v2-ingress.enabled=true")
                    .withBean(TurnAttemptExecutionRunner.class, () -> v2Runner(scheduler))
                    .withBean(ICanvasStateStore.class, () -> mock(ICanvasStateStore.class))
                    .withBean(IDiagramConversationStore.class, () -> mock(IDiagramConversationStore.class))
                    .run(context -> assertThat(context).hasSingleBean(TurnV2ProductIngressAdapter.class));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void productV2IngressFailsClosedWhenTheV2RunnerIsUnavailable() {
        contextRunner
                .withUserConfiguration(TurnV2ProductIngressAdapter.class)
                .withPropertyValues("turn-engine.http.product-v2-ingress.enabled=true")
                .withBean(ICanvasStateStore.class, () -> mock(ICanvasStateStore.class))
                .withBean(IDiagramConversationStore.class, () -> mock(IDiagramConversationStore.class))
                .run(context -> {
                    TurnV2ProductIngressAdapter ingress =
                            context.getBean(TurnV2ProductIngressAdapter.class);

                    assertThat(ingress.chat(
                                    "owner-1", new ChatRequestDTO(), "request-1", "run-1").getContent())
                            .isEqualTo("TURN_V2_EXECUTION_NOT_READY");
                });
    }

    @Test
    void productV2IngressRejectsHistoricalLegacyAssignmentWithoutFallback() {
        TurnHttpDeliveryAdapter delivery = mock(TurnHttpDeliveryAdapter.class);
        TurnHttpControlAdapter control = mock(TurnHttpControlAdapter.class);
        ICanvasStateStore canvases = mock(ICanvasStateStore.class);
        IDiagramConversationStore messages = mock(IDiagramConversationStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TurnAttemptExecutionRunner> runner = mock(ObjectProvider.class);
        when(runner.getIfAvailable()).thenReturn(mock(TurnAttemptExecutionRunner.class));
        when(delivery.executeProductSync(any(), any())).thenReturn(new TurnHttpDeliveryResult(
                new TurnSubmission.LegacyAssignmentPinned(
                        new TurnKey("owner-1", "conversation-1", "request-1")),
                List.of(),
                false));
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        TurnV2ProductIngressAdapter ingress = new TurnV2ProductIngressAdapter(
                delivery, control, canvases, messages, runner,
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()),
                debugTraceProvider(), 1_000L);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setDiagramId("diagram-1");
        request.setMessage("draw it");

        assertThat(ingress.chat("owner-1", request, "request-1", "aru_run-1").getContent())
                .isEqualTo("TURN_V2_LEGACY_ASSIGNMENT_RETIRED");
        assertThat(telemetryStore.runs).singleElement().satisfies(run -> {
            assertThat(run.getRequestType()).isEqualTo("chat");
            assertThat(run.getStatus()).isEqualTo("FAILED");
        });
        assertThat(telemetryStore.steps).singleElement().satisfies(step -> {
            assertThat(step.getPhase()).isEqualTo("turn_v2_execution");
            assertThat(step.getStatus()).isEqualTo("FAILED");
        });
    }

    @Test
    void productV2IngressStopsPollingWhenTheLocalAttemptAlreadyFailed() {
        TurnHttpDeliveryAdapter delivery = mock(TurnHttpDeliveryAdapter.class);
        TurnHttpControlAdapter control = mock(TurnHttpControlAdapter.class);
        ICanvasStateStore canvases = mock(ICanvasStateStore.class);
        IDiagramConversationStore messages = mock(IDiagramConversationStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TurnAttemptExecutionRunner> runner = mock(ObjectProvider.class);
        when(runner.getIfAvailable()).thenReturn(mock(TurnAttemptExecutionRunner.class));

        TurnSubmission.ExecutionAccepted accepted = acceptedTurn();
        TurnHandle handle = mock(TurnHandle.class);
        when(handle.completion()).thenReturn(CompletableFuture.completedFuture(
                new TurnAttemptCompletion.StatusOnly(
                        new TurnStatusRef(accepted.key()), "TERMINAL_UNAVAILABLE")));
        when(delivery.executeProductSync(any(), any())).thenReturn(new TurnHttpDeliveryResult(
                accepted, List.of(), false, handle));
        when(control.status(any(), any())).thenReturn(new TurnStatusQueryOutcome.Available(
                new TurnStatusView(
                        accepted.key(), TurnStatus.RUNNING, accepted.attempt().attemptId(),
                        accepted.attempt().attemptEpoch(), null, null, Instant.now())));

        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        TurnV2ProductIngressAdapter ingress = new TurnV2ProductIngressAdapter(
                delivery, control, canvases, messages, runner,
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()),
                debugTraceProvider(), 1_000L);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setDiagramId("diagram-1");
        request.setMessage("recreate the attached image");

        assertThat(ingress.chat("owner-1", request, "request-1", "run-1").getContent())
                .isEqualTo("TERMINAL_UNAVAILABLE");
    }

    @Test
    void productV2StreamCreatesTheRootRunAndExecutionStep() throws Exception {
        TurnHttpDeliveryAdapter delivery = mock(TurnHttpDeliveryAdapter.class);
        TurnHttpControlAdapter control = mock(TurnHttpControlAdapter.class);
        ICanvasStateStore canvases = mock(ICanvasStateStore.class);
        IDiagramConversationStore messages = mock(IDiagramConversationStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TurnAttemptExecutionRunner> runner = mock(ObjectProvider.class);
        when(runner.getIfAvailable()).thenReturn(mock(TurnAttemptExecutionRunner.class));

        TurnKey key = new TurnKey("owner-1", "conversation-1", "request-1");
        when(delivery.executeProductTracked(any(), any(), any())).thenAnswer(invocation -> {
            TurnEventSink progress = invocation.getArgument(2);
            progress.publish(new TurnEvent(
                    "plain_agent_decision_started",
                    "1\tDECIDE\t\tSTARTED\t0\t0",
                    Instant.now()));
            progress.publish(new TurnEvent(
                    "plain_agent_draft_preview",
                    "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                            + "<mxCell id=\"node-a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"
                            + "</root></mxGraphModel>",
                    Instant.now()));
            progress.publish(new TurnEvent(
                    "plain_agent_visual_review_started",
                    "1\tPOST_MUTATION_REVIEW\tvisual_review_agent\tSTARTED\t0\t0",
                    Instant.now()));
            progress.publish(new TurnEvent(
                    "plain_agent_visual_review_completed",
                    "1\tPOST_MUTATION_REVIEW\tvisual_review_agent\tREPAIR\t25\t1\t"
                            + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            "The hierarchy needs adjustment.".getBytes(StandardCharsets.UTF_8))
                            + "\t"
                            + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            "Move the runtime node below the loader."
                                    .getBytes(StandardCharsets.UTF_8)),
                    Instant.now()));
            return new TurnHttpDeliveryResult(
                    new TurnSubmission.TerminalReplay(
                            key,
                            new PersistedTurnOutcome(
                                    TurnStatus.COMPLETED, "COMPLETED",
                                    "application/json", null, "{}")),
                    List.of(),
                    false);
        });
        when(messages.findAssistantMessage(
                "owner-1", "diagram-1", "conversation-1", "request-1"))
                .thenReturn(Optional.of(DiagramConversationMessage.builder()
                        .userId("owner-1")
                        .diagramId("diagram-1")
                        .turnId("request-1")
                        .role("agent")
                        .content("done")
                        .build()));

        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        TurnV2ProductIngressAdapter ingress = new TurnV2ProductIngressAdapter(
                delivery, control, canvases, messages, runner,
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()),
                debugTraceProvider(), 1_000L);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId("agent-1");
        request.setSessionId("session-1");
        request.setDiagramId("diagram-1");
        request.setMessage("draw it");

        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        ingress.stream(
                "owner-1", request, "request-1", "aru_stream-1", emitter);

        verify(emitter, atLeastOnce()).send(
                argThat(value -> value.toString().contains("\"type\":\"agent_progress\"")),
                any(org.springframework.http.MediaType.class));
        verify(emitter, atLeastOnce()).send(
                argThat(value -> value.toString().contains("\"type\":\"drawio_node\"")),
                any(org.springframework.http.MediaType.class));
        verify(emitter, atLeastOnce()).send(
                argThat(value -> value.toString()
                        .contains("\"stage\":\"visual_review_completed\"")),
                any(org.springframework.http.MediaType.class));
        verify(emitter, atLeastOnce()).send(
                argThat(value -> value.toString()
                        .contains("\"reviewSummary\":\"The hierarchy needs adjustment.\"")
                        && value.toString()
                        .contains("\"reviewFeedback\":[\"Move the runtime node below the loader.\"]")),
                any(org.springframework.http.MediaType.class));

        assertThat(telemetryStore.runs).singleElement().satisfies(run -> {
            assertThat(run.getId()).isEqualTo("aru_stream-1");
            assertThat(run.getRequestType()).isEqualTo("chat_stream");
            assertThat(run.getDiagramId()).isEqualTo("diagram-1");
            assertThat(run.getStatus()).isEqualTo("SUCCESS");
        });
        assertThat(telemetryStore.steps).singleElement().satisfies(step -> {
            assertThat(step.getRunId()).isEqualTo("aru_stream-1");
            assertThat(step.getPhase()).isEqualTo("turn_v2_execution");
            assertThat(step.getStatus()).isEqualTo("SUCCESS");
        });
        assertThat(telemetryStore.traceEvents)
                .extracting(event -> event.getEventType())
                .containsExactly("turn_v2_started", "turn_v2_completed");
    }

    @Test
    void productV2IngressCapturesRunAndStepPayloadsForTheInspector() {
        TurnHttpDeliveryAdapter delivery = mock(TurnHttpDeliveryAdapter.class);
        TurnHttpControlAdapter control = mock(TurnHttpControlAdapter.class);
        ICanvasStateStore canvases = mock(ICanvasStateStore.class);
        IDiagramConversationStore messages = mock(IDiagramConversationStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TurnAttemptExecutionRunner> runner = mock(ObjectProvider.class);
        when(runner.getIfAvailable()).thenReturn(mock(TurnAttemptExecutionRunner.class));
        when(delivery.executeProductSync(any(), any())).thenReturn(new TurnHttpDeliveryResult(
                new TurnSubmission.LegacyAssignmentPinned(
                        new TurnKey("owner-1", "conversation-1", "request-1")),
                List.of(),
                false));
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore traceStore = new FakeDebugTraceStore();
        TurnV2ProductIngressAdapter ingress = new TurnV2ProductIngressAdapter(
                delivery, control, canvases, messages, runner,
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()),
                debugTraceProvider(new AgentDebugTraceService(traceStore, null)), 1_000L);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setDiagramId("diagram-1");
        request.setMessage("draw it");

        ingress.chat("owner-1", request, "request-1", "aru_capture-1");

        // The inspector lazy-loads payloads per span id, so the run root and the execution step
        // must each carry their own rows or their detail panel reads "Not captured".
        assertThat(traceStore.captures)
                .extracting(DebugTraceCapture::getSpanId, DebugTraceCapture::getPayloadKind)
                .contains(tuple("aru_capture-1", "INPUT"), tuple("aru_capture-1", "ERROR"));
        assertThat(traceStore.captures)
                .anySatisfy(capture -> {
                    assertThat(capture.getSpanId()).isNotEqualTo("aru_capture-1");
                    assertThat(capture.getPayloadKind()).isEqualTo("INPUT");
                    assertThat(capture.getContent()).contains("draw it");
                });
    }

    @Test
    void productV2RunsAreAttributedToThePlatformKeyItActuallyUses() {
        TurnHttpDeliveryAdapter delivery = mock(TurnHttpDeliveryAdapter.class);
        TurnHttpControlAdapter control = mock(TurnHttpControlAdapter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TurnAttemptExecutionRunner> runner = mock(ObjectProvider.class);
        when(delivery.executeProductSync(any(), any())).thenReturn(new TurnHttpDeliveryResult(
                new TurnSubmission.LegacyAssignmentPinned(
                        new TurnKey("owner-1", "conversation-1", "request-1")),
                List.of(),
                false));
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        TurnV2ProductIngressAdapter ingress = new TurnV2ProductIngressAdapter(
                delivery, control, mock(ICanvasStateStore.class), mock(IDiagramConversationStore.class),
                runner, new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()),
                debugTraceProvider(null), 1_000L);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setDiagramId("diagram-1");
        request.setMessage("draw it");
        request.setModelCredentialId("mc_user_owned");

        ingress.chat("owner-1", request, "request-1", "aru_credential-1");

        // V2 never installs the caller's credential, so claiming USER_KEY would bill a key that
        // made no call. Every LLM span copies this attribution straight off the run context.
        assertThat(telemetryStore.runs).singleElement().satisfies(run -> {
            assertThat(run.getCredentialSource()).isEqualTo(AgentUsageTelemetryService.PLATFORM);
            assertThat(run.getModelCredentialId()).isNull();
        });
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentDebugTraceService> debugTraceProvider() {
        return debugTraceProvider(null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentDebugTraceService> debugTraceProvider(AgentDebugTraceService service) {
        ObjectProvider<AgentDebugTraceService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private final List<DebugTraceCapture> captures = new ArrayList<>();

        @Override
        public void insertControl(DebugTraceControl control) {
        }

        @Override
        public List<DebugTraceControl> listEnabledControls() {
            return List.of();
        }

        @Override
        public void insertCapture(DebugTraceCapture capture) {
            captures.add(capture);
        }

        @Override
        public int deleteExpiredContent(Instant now) {
            return 0;
        }

        @Override
        public int extendRunContentExpiry(String runId, Instant expiresAt) {
            return 0;
        }
    }

    private static TurnSubmission.ExecutionAccepted acceptedTurn() {
        FencedAttempt attempt = new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "request-1"),
                new AttemptLease(
                        "attempt-1", 1, Instant.parse("2026-07-27T00:00:30Z"), 30_000),
                1,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "policy-hash"));
        return new TurnSubmission.ExecutionAccepted(
                attempt.key(), attempt, new LeaseTimingAnchor(System.nanoTime(), attempt.lease()));
    }

    private static TurnAttemptExecutionRunner v2Runner(ScheduledExecutorService scheduler) {
        TurnV2TurnExecutor executor = new TurnV2TurnExecutor() {
            @Override
            public TurnAttemptCompletion execute(
                    org.zipp.ai.application.turn.TurnSubmission.ExecutionAccepted accepted,
                    UserTurnCommand command,
                    TurnEventSink events
            ) {
                return new TurnAttemptCompletion.AttemptSelfAborted(
                        new org.zipp.ai.application.turn.TurnStatusRef(accepted.key()), "TEST_ONLY");
            }

            @Override
            public void disableWritesAndDrain(org.zipp.ai.application.turn.FencedAttempt attempt) {
                // The composition contract does not execute the supplied runner.
            }
        };
        TurnAttemptLeaseSupervisor heartbeat = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(30)),
                executor);
        return new TurnAttemptExecutionRunner(executor, heartbeat, Runnable::run, scheduler);
    }

    private static final class FakeInstanceLock implements SingleActiveInstanceLock {
        private boolean acquired;

        @Override
        public InstanceLockOutcome acquire(InstanceBootId bootId) {
            acquired = true;
            return InstanceLockOutcome.ACQUIRED;
        }

        @Override
        public boolean isHeld(InstanceBootId bootId) {
            return acquired;
        }
    }

    private static final class FakeOrphanReconciler implements StartupOrphanReconciler {
        private int calls;

        @Override
        public int reconcile(InstanceBootId currentBootId) {
            calls++;
            return 0;
        }
    }

    private static final class FakeMigrationState implements TurnEngineMigrationStatePort {
        @Override
        public MigrationStateSnapshot current() {
            // Composition tests start from the canary gate before exercising ALL_V2 cutover.
            return new MigrationStateSnapshot(0, TurnEngineMode.V2_CANARY, Instant.parse("2026-07-25T00:00:00Z"));
        }
    }

    private static final class FakeAssignments implements TurnEngineAssignmentPort {
        @Override
        public AdmissionWriteOutcome assignOrReuse(TurnEngineAssignmentCommand command) {
            return new AdmissionWriteOutcome.Rejected(command.key(), "TEST_ONLY");
        }
    }

    private static final class FakeMigrationControl implements TurnEngineMigrationControlPort {
        private final List<MigrationModeSwitchCommand> commands = new ArrayList<>();

        @Override
        public MigrationModeSwitchOutcome switchMode(MigrationModeSwitchCommand command) {
            commands.add(command);
            return new MigrationModeSwitchOutcome.Changed(new MigrationStateSnapshot(
                    command.expectedGeneration() + 1,
                    command.targetMode(),
                    Instant.parse("2026-07-26T00:00:00Z")));
        }
    }

    private static final class FakeExpiry implements LegacyRetryExpiryPort {
        @Override
        public int backfillRetryable(int batchSize) {
            return 0;
        }

        @Override
        public int expireDue(int batchSize) {
            return 0;
        }
    }

    private static TurnStatusQueryPort fakeStatus() {
        return (actor, query) -> new TurnStatusQueryOutcome.Available(
                new TurnStatusView(query.key(), TurnStatus.RUNNING, "attempt-1", 1,
                        null, null, java.time.Instant.parse("2026-07-26T00:00:00Z")));
    }

    private static ExplicitTurnCancellationPort fakeCancellation() {
        return (actor, command) -> new CancelTurnOutcome.Rejected("TEST_ONLY");
    }

    private static TurnAttemptLeasePort fakeLease() {
        return attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1));
    }

    private static AttemptDeadlineCancellationPort fakeDeadline() {
        return (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY");
    }

    private static TurnAttemptTakeoverPort fakeTakeover() {
        return key -> new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
    }

    private static final class FakeConversationCatalog implements ConversationCatalogPort {
        @Override
        public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
            return active(actor, diagramId);
        }

        @Override
        public ConversationRef requireActiveBinding(AuthenticatedActor actor, String conversationId, String diagramId) {
            return active(actor, diagramId);
        }

        @Override
        public ConversationRef resolveLegacyAlias(AuthenticatedActor actor, String legacySessionId, String diagramId) {
            return active(actor, diagramId);
        }

        private ConversationRef active(AuthenticatedActor actor, String diagramId) {
            return new ConversationRef("conversation-test", actor.ownerKey(), diagramId, ConversationStatus.ACTIVE);
        }
    }

    private static final class FakeTurnStart implements TurnStartCommitPort {
        @Override
        public TurnStartOutcome start(TurnStartCommand command) {
            throw new UnsupportedOperationException("not used by composition test");
        }
    }
}
