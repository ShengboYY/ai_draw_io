package org.zipp.ai.test.trigger.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.zipp.ai.api.dto.AdminDebugTraceControlDTO;
import org.zipp.ai.api.dto.AdminDebugTraceControlRequestDTO;
import org.zipp.ai.api.dto.AdminDebugTraceCaptureDTO;
import org.zipp.ai.api.dto.AdminDiagramFindingDTO;
import org.zipp.ai.api.dto.AdminDiagramTraceDTO;
import org.zipp.ai.api.dto.AdminDiagramTraceSpanDTO;
import org.zipp.ai.api.dto.AdminLlmCallDTO;
import org.zipp.ai.api.dto.AdminRunDetailDTO;
import org.zipp.ai.api.dto.AdminUsageDashboardDTO;
import org.zipp.ai.api.dto.AdminUserDTO;
import org.zipp.ai.api.dto.DiagramCanvasStateResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.PasswordResetResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalIntakeService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalDraftService;
import org.zipp.ai.domain.agent.service.evaluation.intake.IEvalDraftModel;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.AdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.trigger.http.service.AuthenticatedSessionUser;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdminControllerTest {

    private FakeAccountService accounts;
    private FakeAgentUsageTelemetryStore telemetryStore;
    private FakeAdminAuditLogStore auditLogs;
    private AdminAuditLogService auditService;
    private FakeDebugTraceStore debugTraceStore;
    private FakeCanvasStateStore canvasStateStore;
    private FakeTraceToEvalStore traceToEvalStore;
    private AdminController controller;

    @Before
    public void setUp() throws Exception {
        accounts = new FakeAccountService();
        telemetryStore = new FakeAgentUsageTelemetryStore();
        auditLogs = new FakeAdminAuditLogStore();
        debugTraceStore = new FakeDebugTraceStore();
        canvasStateStore = new FakeCanvasStateStore();
        traceToEvalStore = new FakeTraceToEvalStore();
        auditService = new AdminAuditLogService(
                auditLogs, Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC));
        AdminAuthorizationService authorizationService = new AdminAuthorizationService();
        inject(authorizationService, "accountService", accounts);
        inject(authorizationService, "adminEmails", "admin@example.com");

        controller = new AdminController();
        inject(controller, "accountService", accounts);
        inject(controller, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore));
        inject(controller, "adminAuditLogService", auditService);
        inject(controller, "agentDebugTraceService", new AgentDebugTraceService(
                debugTraceStore, auditService, Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC)));
        inject(controller, "adminAuthorizationService", authorizationService);
        inject(controller, "canvasStateStore", canvasStateStore);
        inject(controller, "traceToEvalIntakeService", new TraceToEvalIntakeService(telemetryStore, traceToEvalStore));

        accounts.put(activeUser("usr_admin", "admin@example.com"));
        accounts.put(activeUser("usr_user", "user@example.com"));
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void nonAdminCannotAccessAdminEndpoints() {
        authenticate("usr_user", 0);

        Response<List<AdminUserDTO>> response = controller.listUsers(request());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(auditLogs.logs.isEmpty());
    }

    @Test
    public void adminCanCreateMetadataOnlyEvalCandidateAndWritesAuditLog() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_eval")
                .agentId("drawing-agent")
                .status("SUCCESS")
                .build());

        Response<EvalCaseCandidate> response = controller.createEvalCandidate("aru_eval", request());

        assertEquals("0000", response.getCode());
        assertEquals("aru_eval", response.getData().getSourceRunId());
        assertEquals(EvalCandidateStatus.DETECTED, response.getData().getStatus());
        assertEquals(1, traceToEvalStore.candidates.size());
        assertEquals(0, debugTraceStore.listCaptureRequests);
        assertEquals("CREATE_EVAL_CANDIDATE", auditLogs.logs.get(0).getAction());
    }

    @Test
    public void legacyCandidateApprovalAndPublicationEndpointsAreNotExposed() {
        List<String> postMappings = java.util.Arrays.stream(AdminController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .toList();

        assertFalse(postMappings.contains("/eval-candidates/{candidateId}/review"));
        assertFalse(postMappings.contains("/eval-candidates/{candidateId}/publication"));
    }

    @Test
    public void evalCandidateInfrastructureFailureIsAuditedWithoutLeakingDetails() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder().id("aru_eval").status("SUCCESS").build());
        traceToEvalStore.failInsertCandidate = true;

        Response<EvalCaseCandidate> response = controller.createEvalCandidate("aru_eval", request());

        assertEquals("failed to create Eval Candidate", response.getInfo());
        assertEquals("ERROR", auditLogs.logs.get(0).getOutcome());
    }

    @Test
    public void adminCanFilterAndTriageTheEvalCandidateQueue() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder().id("aru_eval").status("FAILED").build());
        EvalCaseCandidate candidate = controller.createEvalCandidate("aru_eval", request()).getData();

        Response<EvalCaseCandidate> transitioned = controller.transitionEvalCandidate(candidate.getId(),
                Map.of("status", "TRIAGED", "reason", "high-value regression"), request());
        Response<List<EvalCaseCandidate>> listed = controller.listEvalCandidates("TRIAGED", "high", 50, 0, request());

        assertEquals(EvalCandidateStatus.TRIAGED, transitioned.getData().getStatus());
        assertEquals(1, listed.getData().size());
        assertEquals("LIST_EVAL_CANDIDATES", auditLogs.logs.get(2).getAction());
    }

    @Test
    public void adminMustExplicitlyConfirmAndReceivesOnlyASyntheticDraft() throws Exception {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder().id("aru_eval").status("FAILED").build());
        EvalCaseCandidate candidate = controller.createEvalCandidate("aru_eval", request()).getData();
        controller.transitionEvalCandidate(candidate.getId(), Map.of("status", "TRIAGED"), request());
        debugTraceStore.captures.add(DebugTraceCapture.builder().id("capture").runId("aru_eval")
                .content("User requested a worker; email alice@example.com")
                .contentExpiresAt(Instant.parse("2026-07-10T10:00:00Z")).build());
        IEvalDraftModel model = new IEvalDraftModel() {
            public String generate(String prompt) { return "{\"failure_summary\":\"Mutation failed\",\"suspected_failure_family\":\"artifact\",\"suggested_case\":{\"user_turns\":[\"Add worker\"],\"initial_fixture_hint\":\"synthetic\",\"expected_route\":\"edit_existing\",\"suggested_assertions\":[\"worker exists\"]},\"confidence\":\"medium\",\"needs_human_review\":true}"; }
            public String version() { return "fake-v1"; }
        };
        inject(controller, "traceToEvalDraftService", new TraceToEvalDraftService(traceToEvalStore,
                new AgentDebugTraceService(debugTraceStore, auditService,
                        Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC)), model));

        Response<TraceToEvalDraftService.Preparation> response = controller.prepareEvalDraft(candidate.getId(),
                Map.of("purposeConfirmed", true), request());

        assertEquals(EvalCandidateStatus.DRAFT_READY, response.getData().status());
        assertTrue(response.getData().draft().isNeedsHumanReview());
        assertEquals("PREPARE_EVAL_DRAFT", auditLogs.logs.get(auditLogs.logs.size() - 1).getAction());
    }

    @Test
    public void adminRequestFromUntrustedOriginIsRejected() {
        authenticate("usr_admin", 0);
        MockHttpServletRequest request = request();
        request.addHeader("Origin", "https://evil.example");

        Response<List<AdminUserDTO>> response = controller.listUsers(request);

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(auditLogs.logs.isEmpty());
    }

    @Test
    public void adminCanDisableUserAndWritesAuditLog() {
        authenticate("usr_admin", 0);

        Response<AdminUserDTO> response = controller.disableUser("usr_user", request());

        assertEquals("0000", response.getCode());
        assertEquals(AccountStatus.DISABLED.name(), response.getData().getStatus());
        UserAccount disabled = accounts.findById("usr_user").orElseThrow();
        assertEquals(AccountStatus.DISABLED, disabled.getStatus());
        assertEquals(1, disabled.getSessionVersion());
        assertEquals(1, auditLogs.logs.size());
        assertEquals("DISABLE_USER", auditLogs.logs.get(0).getAction());
        assertEquals("usr_user", auditLogs.logs.get(0).getTargetId());
    }

    @Test
    public void nonAdminCannotEnableDebugTrace() {
        authenticate("usr_user", 0);
        AdminDebugTraceControlRequestDTO dto = new AdminDebugTraceControlRequestDTO();
        dto.setUserId("usr_user");

        Response<AdminDebugTraceControlDTO> response = controller.enableDebugTrace(dto, request());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(debugTraceStore.controls.isEmpty());
        assertTrue(auditLogs.logs.isEmpty());
    }

    @Test
    public void adminCanEnableUserScopedDebugTraceAndWritesAuditLog() {
        authenticate("usr_admin", 0);
        AdminDebugTraceControlRequestDTO dto = new AdminDebugTraceControlRequestDTO();
        dto.setUserId("usr_user");

        Response<AdminDebugTraceControlDTO> response = controller.enableDebugTrace(dto, request());

        assertEquals("0000", response.getCode());
        assertEquals("usr_user", response.getData().getScopeUserId());
        assertEquals(1, debugTraceStore.controls.size());
        assertEquals(1, auditLogs.logs.size());
        assertEquals("ENABLE_DEBUG_TRACE", auditLogs.logs.get(0).getAction());
        assertEquals(response.getData().getId(), auditLogs.logs.get(0).getTargetId());
    }

    @Test
    public void adminCanViewDebugTraceCapturesAndWritesViewAudit() {
        authenticate("usr_admin", 0);
        debugTraceStore.captures.add(DebugTraceCapture.builder()
                .id("adt_1")
                .controlId("dtc_1")
                .userId("usr_user")
                .runId("aru_1")
                .eventType("CHAT_REQUEST")
                .content("debug prompt")
                .contentSha256("sha")
                .contentExpiresAt(Instant.parse("2026-07-10T10:00:00Z"))
                .createdAt(Instant.parse("2026-07-03T10:00:00Z"))
                .build());

        Response<List<AdminDebugTraceCaptureDTO>> response = controller.viewDebugTraceCaptures("aru_1", request());

        assertEquals("0000", response.getCode());
        assertEquals("debug prompt", response.getData().get(0).getContent());
        assertEquals(1, auditLogs.logs.size());
        assertEquals("VIEW_DEBUG_TRACE_CAPTURE", auditLogs.logs.get(0).getAction());
        assertEquals("aru_1", auditLogs.logs.get(0).getTargetId());
    }

    @Test
    public void adminCanLoadPayloadsForOneSelectedSpan() {
        authenticate("usr_admin", 0);
        debugTraceStore.captures.add(DebugTraceCapture.builder()
                .id("adt_input")
                .controlId("dtc_1")
                .userId("usr_user")
                .runId("aru_1")
                .spanId("alc_1")
                .eventType("INPUT")
                .payloadKind("INPUT")
                .contentType("application/json")
                .content("{\"messages\":[]}")
                .contentSha256("sha")
                .originalLength(15L)
                .truncated(false)
                .contentExpiresAt(Instant.parse("2026-07-10T10:00:00Z"))
                .createdAt(Instant.parse("2026-07-03T10:00:00Z"))
                .build());
        debugTraceStore.captures.add(DebugTraceCapture.builder()
                .id("adt_other")
                .runId("aru_1")
                .spanId("atc_2")
                .payloadKind("TOOL_RESULT")
                .content("other")
                .build());

        Response<List<AdminDebugTraceCaptureDTO>> response = controller.viewSpanPayloads(
                "aru_1", "alc_1", request());

        assertEquals("0000", response.getCode());
        assertEquals(1, response.getData().size());
        assertEquals("alc_1", response.getData().get(0).getSpanId());
        assertEquals("INPUT", response.getData().get(0).getPayloadKind());
        assertEquals("application/json", response.getData().get(0).getContentType());
        assertFalse(response.getData().get(0).isTruncated());
    }

    @Test
    public void adminUsageAndRunDetailExposeOnlyMetadata() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_1")
                .diagramId("diag_admin_1")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat")
                .credentialSource("USER_KEY")
                .modelCredentialId("mcr_1")
                .status("SUCCESS")
                .latencyMs(120L)
                .stepCount(2L)
                .llmCallCount(1L)
                .toolCallCount(3L)
                .traceEventCount(4L)
                .knownTotalTokens(25L)
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.llmCalls.add(LlmCallTelemetry.builder()
                .id("alc_1")
                .runId("aru_1")
                .userId("usr_user")
                .phase("drawing")
                .provider("openai")
                .model("gpt-test")
                .credentialSource("USER_KEY")
                .modelCredentialId("mcr_1")
                .promptTokens(10)
                .completionTokens(15)
                .totalTokens(25)
                .status("SUCCESS")
                .latencyMs(80L)
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .build());

        Response<AdminUsageDashboardDTO> usage = controller.usage(request());
        Response<AdminRunDetailDTO> detail = controller.runDetail("aru_1", request());

        assertEquals("0000", usage.getCode());
        assertEquals(Long.valueOf(1), usage.getData().getRequestCount());
        assertEquals("openai", usage.getData().getGroups().get(0).getProvider());
        assertEquals("0000", detail.getCode());
        assertEquals("mcr_1", detail.getData().getRun().getModelCredentialId());
        assertEquals("diag_admin_1", detail.getData().getRun().getDiagramId());
        assertEquals(Long.valueOf(2), detail.getData().getRun().getStepCount());
        assertEquals(Long.valueOf(1), detail.getData().getRun().getLlmCallCount());
        assertEquals(Long.valueOf(3), detail.getData().getRun().getToolCallCount());
        assertEquals(Long.valueOf(4), detail.getData().getRun().getTraceEventCount());
        assertEquals(Long.valueOf(25), detail.getData().getRun().getKnownTotalTokens());
        assertNoRawKeyFields(AdminLlmCallDTO.class);
        assertFalse(detail.getData().toString().contains("sk-live-secret"));
    }

    @Test
    public void runDetailIncludesUnifiedMetadataTimeline() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_timeline")
                .requestId("req-timeline")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .credentialSource("PLATFORM")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.traceEvents.add(AgentTraceEvent.builder()
                .id("ate_1")
                .runId("aru_timeline")
                .requestId("req-timeline")
                .userId("usr_user")
                .sequenceNo(1L)
                .eventType("HTTP_REQUEST_RECEIVED")
                .phase("request")
                .status("SUCCESS")
                .occurredAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.steps.add(AgentRunStepTelemetry.builder()
                .id("ars_1")
                .runId("aru_timeline")
                .userId("usr_user")
                .phase("routing")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:02Z"))
                .latencyMs(1000L)
                .build());
        telemetryStore.llmCalls.add(LlmCallTelemetry.builder()
                .id("alc_1")
                .runId("aru_timeline")
                .userId("usr_user")
                .phase("routing")
                .provider("openai")
                .model("gpt-test")
                .credentialSource("PLATFORM")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .latencyMs(800L)
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_1")
                .runId("aru_timeline")
                .userId("usr_user")
                .phase("drawing")
                .toolName("create_diagram")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:03Z"))
                .build());

        Response<AdminRunDetailDTO> detail = controller.runDetail("aru_timeline", request());

        assertEquals("0000", detail.getCode());
        assertEquals("req-timeline", detail.getData().getRun().getRequestId());
        assertEquals(4, detail.getData().getTimeline().size());
        assertEquals("trace_event", detail.getData().getTimeline().get(0).getSource());
        assertEquals("HTTP_REQUEST_RECEIVED", detail.getData().getTimeline().get(0).getEventType());
        assertEquals("llm_call", detail.getData().getTimeline().get(2).getSource());
        assertEquals("tool_call", detail.getData().getTimeline().get(3).getSource());
    }

    @Test
    public void diagramTraceReturnsUnifiedSpanModel() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_trace")
                .requestId("req-trace")
                .diagramId("diag_trace")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:10Z"))
                .latencyMs(10_000L)
                .stepCount(1L)
                .llmCallCount(1L)
                .toolCallCount(1L)
                .traceEventCount(2L)
                .knownTotalTokens(3_000L)
                .build());
        telemetryStore.traceEvents.add(AgentTraceEvent.builder()
                .id("ate_trace")
                .runId("aru_trace")
                .requestId("req-trace")
                .userId("usr_user")
                .sequenceNo(1L)
                .eventType("HTTP_REQUEST_RECEIVED")
                .phase("request")
                .status("SUCCESS")
                .occurredAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.traceEvents.add(AgentTraceEvent.builder()
                .id("ate_canvas")
                .runId("aru_trace")
                .requestId("req-trace")
                .userId("usr_user")
                .sequenceNo(2L)
                .eventType("CANVAS_SAVED")
                .phase("diagram")
                .status("SUCCESS")
                .occurredAt(Instant.parse("2026-07-03T09:00:06Z"))
                .build());
        telemetryStore.steps.add(AgentRunStepTelemetry.builder()
                .id("ars_trace")
                .runId("aru_trace")
                .userId("usr_user")
                .phase("drawing")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:08Z"))
                .latencyMs(7_000L)
                .build());
        telemetryStore.llmCalls.add(LlmCallTelemetry.builder()
                .id("alc_trace")
                .runId("aru_trace")
                .parentId("ars_trace")
                .userId("usr_user")
                .phase("drawing")
                .provider("openai")
                .model("gpt-4o-test")
                .promptTokens(1000)
                .completionTokens(2000)
                .totalTokens(3000)
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:02Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:04Z"))
                .latencyMs(2_000L)
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_trace")
                .runId("aru_trace")
                .parentId("ars_trace")
                .userId("usr_user")
                .phase("drawing")
                .toolName("create_diagram")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:05Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:06Z"))
                .latencyMs(1_000L)
                .build());
        canvasStateStore.state = CanvasState.builder()
                .userId("usr_user")
                .diagramId("diag_trace")
                .currentXml("<mxfile/>")
                .contentHash("hash-final")
                .thumbnailUrl("data:image/png;base64,abc")
                .version(3L)
                .updatedAt(java.util.Date.from(Instant.parse("2026-07-03T09:00:07Z")))
                .build();

        Response<AdminDiagramTraceDTO> response = controller.diagramTrace("aru_trace", request());

        assertEquals("0000", response.getCode());
        assertEquals("aru_trace", response.getData().getRun().getId());
        assertEquals("DIAGRAM_CREATED", response.getData().getSummary().getOutcome());
        assertEquals(Long.valueOf(6), response.getData().getSummary().getSpanCount());
        assertEquals(6, response.getData().getSpans().size());
        assertEquals("RUN", response.getData().getSpans().get(0).getKind());
        assertEquals("EVENT", response.getData().getSpans().get(1).getKind());
        assertEquals("aru_trace", response.getData().getSpans().get(2).getParentId());
        assertEquals("usr_user", canvasStateStore.requestedUserId);
        assertEquals("diag_trace", canvasStateStore.requestedDiagramId);

        AdminDiagramTraceSpanDTO llm = response.getData().getSpans().stream()
                .filter(span -> "LLM".equals(span.getKind()))
                .findFirst()
                .orElseThrow();
        assertEquals("openai", llm.getProvider());
        assertEquals("gpt-4o-test", llm.getModel());
        assertEquals(Integer.valueOf(3000), llm.getTotalTokens());
        assertTrue(llm.getEstimatedCost() > 0D);

        AdminDiagramTraceSpanDTO tool = response.getData().getSpans().stream()
                .filter(span -> "TOOL".equals(span.getKind()))
                .findFirst()
                .orElseThrow();
        assertEquals("create_diagram", tool.getToolName());
        assertEquals("ars_trace", tool.getParentId());
        assertEquals("diag_trace", tool.getDiagramEffect().getDiagramId());
        assertEquals(Long.valueOf(3L), tool.getDiagramEffect().getAfterVersion());
        assertEquals("hash-final", tool.getDiagramEffect().getAfterHash());
        assertEquals("THUMBNAIL_RENDERED", tool.getDiagramEffect().getRenderStatus());
        assertEquals("data:image/png;base64,abc", tool.getDiagramEffect().getThumbnailUrl());

        AdminDiagramTraceSpanDTO canvasEvent = response.getData().getSpans().stream()
                .filter(span -> "CANVAS_SAVED".equals(span.getEventType()))
                .findFirst()
                .orElseThrow();
        assertEquals("diag_trace", canvasEvent.getDiagramEffect().getDiagramId());
        assertEquals("ON_DEMAND", response.getData().getPayloadAvailability().getStatus());
    }

    @Test
    public void diagramTraceComputesRuntimeFindings() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_findings")
                .requestId("req-findings")
                .diagramId("diag_findings")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .latencyMs(75_000L)
                .knownTotalTokens(40_000L)
                .build());
        telemetryStore.llmCalls.add(LlmCallTelemetry.builder()
                .id("alc_fail")
                .runId("aru_findings")
                .userId("usr_user")
                .phase("drawing")
                .provider("openai")
                .model("gpt-4o-test")
                .promptTokens(20_000)
                .completionTokens(20_000)
                .totalTokens(40_000)
                .status("FAILED")
                .errorClass("ProviderTimeout")
                .latencyMs(70_000L)
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_fail")
                .runId("aru_findings")
                .userId("usr_user")
                .phase("drawing")
                .toolName("apply_visual_repair")
                .status("FAILED")
                .errorClass("DrawioToolError")
                .latencyMs(65_000L)
                .startedAt(Instant.parse("2026-07-03T09:00:05Z"))
                .build());
        canvasStateStore.state = CanvasState.builder()
                .userId("usr_user")
                .diagramId("diag_findings")
                .currentXml("<diagram/>")
                .contentHash("hash-invalid")
                .version(2L)
                .updatedAt(java.util.Date.from(Instant.parse("2026-07-03T09:00:07Z")))
                .build();

        Response<AdminDiagramTraceDTO> response = controller.diagramTrace("aru_findings", request());

        assertEquals("0000", response.getCode());
        List<AdminDiagramFindingDTO> findings = response.getData().getFindings();
        List<String> codes = findings.stream().map(AdminDiagramFindingDTO::getCode).toList();
        assertTrue(codes.contains("INVALID_XML"));
        assertTrue(codes.contains("THUMBNAIL_MISSING"));
        assertTrue(codes.contains("LLM_FAILED"));
        assertTrue(codes.contains("TOOL_FAILED"));
        assertTrue(codes.contains("HIGH_COST"));
        assertTrue(codes.contains("SLOW_SPAN"));

        AdminDiagramFindingDTO toolFailed = findings.stream()
                .filter(finding -> "TOOL_FAILED".equals(finding.getCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("ERROR", toolFailed.getSeverity());
        assertEquals("atc_fail", toolFailed.getSpanId());
        assertEquals("diag_findings", toolFailed.getDiagramId());
        assertEquals("DIAGRAM_MODIFIED", response.getData().getSummary().getOutcome());
        AdminDiagramTraceSpanDTO repairTool = response.getData().getSpans().stream()
                .filter(span -> "apply_visual_repair".equals(span.getToolName()))
                .findFirst()
                .orElseThrow();
        assertEquals("diag_findings", repairTool.getDiagramEffect().getDiagramId());
    }

    @Test
    public void diagramTraceReturnsCurrentDiagramSnapshot() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_snapshot")
                .requestId("req-snapshot")
                .diagramId("diag_snapshot")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_snapshot")
                .runId("aru_snapshot")
                .userId("usr_user")
                .phase("drawing")
                .toolName("create_diagram")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:03Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:05Z"))
                .latencyMs(2_000L)
                .build());
        canvasStateStore.state = CanvasState.builder()
                .userId("usr_user")
                .diagramId("diag_snapshot")
                .currentXml("<mxfile/>")
                .contentHash("hash-snapshot")
                .thumbnailUrl("data:image/png;base64,snapshot")
                .summary("Final saved diagram")
                .version(4L)
                .updatedAt(java.util.Date.from(Instant.parse("2026-07-03T09:00:06Z")))
                .build();

        Response<AdminDiagramTraceDTO> response = controller.diagramTrace("aru_snapshot", request());

        assertEquals("0000", response.getCode());
        assertEquals(1, response.getData().getSnapshots().size());
        assertEquals("aru_snapshot", response.getData().getSnapshots().get(0).getRunId());
        assertEquals("atc_snapshot", response.getData().getSnapshots().get(0).getSpanId());
        assertEquals("diag_snapshot", response.getData().getSnapshots().get(0).getDiagramId());
        assertEquals(Long.valueOf(4L), response.getData().getSnapshots().get(0).getVersion());
        assertEquals("hash-snapshot", response.getData().getSnapshots().get(0).getCanvasHash());
        assertEquals("data:image/png;base64,snapshot", response.getData().getSnapshots().get(0).getThumbnailUrl());
        assertEquals("Final saved diagram", response.getData().getSnapshots().get(0).getSummary());
        assertEquals(Instant.parse("2026-07-03T09:00:06Z"), response.getData().getSnapshots().get(0).getCreatedAt());
    }

    @Test
    public void diagramTraceReturnsPersistedSnapshotsBeforeFallback() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_persisted_snapshot")
                .requestId("req-persisted-snapshot")
                .diagramId("diag_persisted")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.diagramSnapshots.add(AgentDiagramTraceSnapshot.builder()
                .id("ads_1")
                .runId("aru_persisted_snapshot")
                .spanId("ars_drawing")
                .diagramId("diag_persisted")
                .version(1L)
                .canvasHash("hash-v1")
                .thumbnailUrl("data:image/png;base64,v1")
                .summary("CREATED")
                .createdAt(Instant.parse("2026-07-03T09:00:05Z"))
                .build());
        telemetryStore.diagramSnapshots.add(AgentDiagramTraceSnapshot.builder()
                .id("ads_2")
                .runId("aru_persisted_snapshot")
                .spanId("ars_drawing")
                .diagramId("diag_persisted")
                .version(2L)
                .canvasHash("hash-v2")
                .thumbnailUrl("data:image/png;base64,v2")
                .summary("UPDATED")
                .createdAt(Instant.parse("2026-07-03T09:00:08Z"))
                .build());
        canvasStateStore.state = CanvasState.builder()
                .userId("usr_user")
                .diagramId("diag_persisted")
                .currentXml("<mxfile/>")
                .contentHash("hash-current")
                .version(3L)
                .updatedAt(java.util.Date.from(Instant.parse("2026-07-03T09:00:10Z")))
                .build();

        Response<AdminDiagramTraceDTO> response = controller.diagramTrace("aru_persisted_snapshot", request());

        assertEquals("0000", response.getCode());
        assertEquals(2, response.getData().getSnapshots().size());
        assertEquals("ads_1", response.getData().getSnapshots().get(0).getId());
        assertEquals("hash-v1", response.getData().getSnapshots().get(0).getCanvasHash());
        assertEquals("ads_2", response.getData().getSnapshots().get(1).getId());
        assertEquals("hash-v2", response.getData().getSnapshots().get(1).getCanvasHash());
    }

    @Test
    public void diagramTraceAttachesBeforeAndAfterSnapshotDiffToMutationSpan() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_snapshot_diff")
                .requestId("req-snapshot-diff")
                .diagramId("diag_diff")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_create_diff")
                .runId("aru_snapshot_diff")
                .userId("usr_user")
                .toolName("create_diagram")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:02Z"))
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_modify_diff")
                .runId("aru_snapshot_diff")
                .userId("usr_user")
                .toolName("modify_diagram")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:05Z"))
                .build());
        telemetryStore.steps.add(AgentRunStepTelemetry.builder()
                .id("ars_drawing_diff")
                .runId("aru_snapshot_diff")
                .userId("usr_user")
                .phase("drawing")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:07Z"))
                .build());
        telemetryStore.diagramSnapshots.add(AgentDiagramTraceSnapshot.builder()
                .id("ads_diff_1")
                .runId("aru_snapshot_diff")
                .spanId("ars_drawing_diff")
                .diagramId("diag_diff")
                .version(1L)
                .canvasHash("hash-before")
                .thumbnailUrl("thumb-before")
                .createdAt(Instant.parse("2026-07-03T09:00:03Z"))
                .build());
        telemetryStore.diagramSnapshots.add(AgentDiagramTraceSnapshot.builder()
                .id("ads_diff_2")
                .runId("aru_snapshot_diff")
                .spanId("ars_drawing_diff")
                .diagramId("diag_diff")
                .version(2L)
                .canvasHash("hash-after")
                .thumbnailUrl("thumb-after")
                .changedCellCount(2)
                .createdAt(Instant.parse("2026-07-03T09:00:06Z"))
                .build());
        canvasStateStore.state = CanvasState.builder()
                .userId("usr_user")
                .diagramId("diag_diff")
                .currentXml("<mxfile/>")
                .contentHash("hash-current")
                .version(3L)
                .build();

        Response<AdminDiagramTraceDTO> response = controller.diagramTrace("aru_snapshot_diff", request());

        AdminDiagramTraceSpanDTO drawingStep = response.getData().getSpans().stream()
                .filter(span -> "ars_drawing_diff".equals(span.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("diag_diff", drawingStep.getDiagramEffect().getDiagramId());
        assertEquals(Long.valueOf(1L), drawingStep.getDiagramEffect().getBeforeVersion());
        assertEquals(Long.valueOf(2L), drawingStep.getDiagramEffect().getAfterVersion());
        assertEquals("hash-before", drawingStep.getDiagramEffect().getBeforeHash());
        assertEquals("hash-after", drawingStep.getDiagramEffect().getAfterHash());
        assertEquals(Boolean.TRUE, drawingStep.getDiagramEffect().getXmlChanged());
        assertEquals(Boolean.TRUE, drawingStep.getDiagramEffect().getThumbnailChanged());
        assertEquals(Integer.valueOf(2), drawingStep.getDiagramEffect().getChangedCellCount());
        assertEquals("thumb-after", drawingStep.getDiagramEffect().getThumbnailUrl());
    }

    @Test
    public void adminCanViewRunDiagramUsingRunOwnerAndDiagramId() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_diagram")
                .diagramId("diag_123")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .credentialSource("PLATFORM")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        canvasStateStore.state = CanvasState.builder()
                .userId("usr_user")
                .diagramId("diag_123")
                .title("Checkout flow")
                .currentXml("<mxfile/>")
                .version(4L)
                .updatedAt(java.util.Date.from(Instant.parse("2026-07-03T09:05:00Z")))
                .build();

        Response<DiagramCanvasStateResponseDTO> response = controller.runDiagram("aru_diagram", request());

        assertEquals("0000", response.getCode());
        assertEquals("usr_user", canvasStateStore.requestedUserId);
        assertEquals("diag_123", canvasStateStore.requestedDiagramId);
        assertEquals("diag_123", response.getData().getDiagramId());
        assertEquals("<mxfile/>", response.getData().getCurrentXml());
    }

    private void assertNoRawKeyFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse("admin DTO must not expose raw API keys", name.contains("apikey"));
            assertFalse("admin DTO must not expose secrets", name.contains("secret"));
            assertFalse("admin DTO must not expose encrypted key material", name.contains("encrypted"));
        }
    }

    private UserAccount activeUser(String id, String email) {
        return UserAccount.builder()
                .id(id)
                .email(email)
                .emailNormalized(email)
                .status(AccountStatus.ACTIVE)
                .sessionVersion(0)
                .createdAt(Instant.parse("2026-07-03T09:00:00Z"))
                .updatedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .verifiedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build();
    }

    private void authenticate(String userId, int sessionVersion) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedSessionUser(userId, sessionVersion),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20");
        request.addHeader("User-Agent", "AdminControllerTest");
        return request;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeAccountService implements IAccountService {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        void put(UserAccount user) {
            byId.put(user.getId(), user);
        }

        @Override public RegistrationResult register(RegisterAccountCommand command) { throw new UnsupportedOperationException(); }
        @Override public EmailVerificationResult verifyEmail(String rawToken) { throw new UnsupportedOperationException(); }
        @Override public void resendVerification(String email) { throw new UnsupportedOperationException(); }
        @Override public void requestPasswordReset(String email) { throw new UnsupportedOperationException(); }
        @Override public PasswordResetResult resetPassword(String rawToken, String rawPassword) { throw new UnsupportedOperationException(); }
        @Override public LoginResult login(LoginAccountCommand command) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<UserAccount> findById(String userId) {
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public List<UserAccount> listUsers() {
            return byId.values().stream()
                    .sorted(Comparator.comparing(UserAccount::getId))
                    .toList();
        }

        @Override
        public Optional<UserAccount> disableUser(String userId) {
            UserAccount user = byId.get(userId);
            if (user == null || user.getStatus() == AccountStatus.DISABLED) {
                return Optional.empty();
            }
            user.setStatus(AccountStatus.DISABLED);
            user.setSessionVersion(user.getSessionVersion() + 1);
            return Optional.of(user);
        }
    }

    private static final class FakeAdminAuditLogStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();

        @Override
        public void insert(AdminAuditLog log) {
            logs.add(log);
        }

        @Override
        public List<AdminAuditLog> listRecent(int limit) {
            return logs.stream().limit(limit).toList();
        }
    }

    private static final class FakeCanvasStateStore implements ICanvasStateStore {
        private CanvasState state;
        private String requestedUserId;
        private String requestedDiagramId;

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            requestedUserId = userId;
            requestedDiagramId = diagramId;
            if (state == null || !userId.equals(state.getUserId()) || !diagramId.equals(state.getDiagramId())) {
                return Optional.empty();
            }
            return Optional.of(state);
        }

        @Override
        public CanvasState save(CanvasState state) {
            this.state = state;
            return state;
        }
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private final List<DebugTraceControl> controls = new ArrayList<>();
        private final List<DebugTraceCapture> captures = new ArrayList<>();
        private int listCaptureRequests;

        @Override
        public void insertControl(DebugTraceControl control) {
            controls.add(control);
        }

        @Override
        public List<DebugTraceControl> listEnabledControls() {
            return controls;
        }

        @Override
        public void insertCapture(DebugTraceCapture capture) {
        }

        @Override
        public int deleteExpiredContent(Instant now) {
            return 0;
        }

        @Override
        public int extendRunContentExpiry(String runId, Instant expiresAt) {
            return 0;
        }

        @Override
        public List<DebugTraceCapture> listCapturesByRunId(String runId) {
            listCaptureRequests++;
            return captures.stream()
                    .filter(capture -> runId.equals(capture.getRunId()))
                    .toList();
        }
    }

    private static final class FakeTraceToEvalStore implements ITraceToEvalStore {
        private final Map<String, EvalCaseCandidate> candidates = new HashMap<>();
        private boolean failInsertCandidate;

        @Override public Optional<EvalCaseCandidate> findCandidate(String candidateId) {
            return Optional.ofNullable(candidates.get(candidateId));
        }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String runId, String family) {
            return candidates.values().stream()
                    .filter(candidate -> runId.equals(candidate.getSourceRunId()) && family.equals(candidate.getFailureFamily()))
                    .findFirst();
        }
        @Override public List<EvalCaseCandidate> listCandidates(EvalCandidateStatus status, String risk, int limit, int offset) {
            return candidates.values().stream()
                    .filter(candidate -> status == null || status == candidate.getStatus())
                    .filter(candidate -> risk == null || risk.equals(candidate.getRisk()))
                    .skip(offset).limit(limit).toList();
        }
        @Override public void insertCandidate(EvalCaseCandidate candidate) {
            if (failInsertCandidate) throw new IllegalStateException("database details");
            candidates.put(candidate.getId(), candidate);
        }
        @Override public void updateCandidateStatus(String candidateId, EvalCandidateStatus status) {
            candidates.get(candidateId).setStatus(status);
        }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }
}
