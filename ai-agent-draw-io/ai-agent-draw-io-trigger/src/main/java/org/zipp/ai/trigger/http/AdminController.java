package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.AdminAuditLogDTO;
import org.zipp.ai.api.dto.AdminDebugTraceCaptureDTO;
import org.zipp.ai.api.dto.AdminDebugTraceControlDTO;
import org.zipp.ai.api.dto.AdminDebugTraceControlRequestDTO;
import org.zipp.ai.api.dto.AdminDebugTraceRetentionRequestDTO;
import org.zipp.ai.api.dto.AdminDiagramEffectDTO;
import org.zipp.ai.api.dto.AdminDiagramFindingDTO;
import org.zipp.ai.api.dto.AdminDiagramSnapshotDTO;
import org.zipp.ai.api.dto.AdminDiagramTraceDTO;
import org.zipp.ai.api.dto.AdminDiagramTraceSpanDTO;
import org.zipp.ai.api.dto.AdminDiagramTraceSummaryDTO;
import org.zipp.ai.api.dto.AdminLlmCallDTO;
import org.zipp.ai.api.dto.AdminPayloadAvailabilityDTO;
import org.zipp.ai.api.dto.AdminRunDetailDTO;
import org.zipp.ai.api.dto.AdminRunMetadataDTO;
import org.zipp.ai.api.dto.AdminRunStepDTO;
import org.zipp.ai.api.dto.AdminRunTimelineEventDTO;
import org.zipp.ai.api.dto.AdminToolCallDTO;
import org.zipp.ai.api.dto.AdminTraceEventDTO;
import org.zipp.ai.api.dto.AdminUsageDashboardDTO;
import org.zipp.ai.api.dto.AdminUsageDimensionDTO;
import org.zipp.ai.api.dto.AdminUserDTO;
import org.zipp.ai.api.dto.DiagramCanvasStateResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalIntakeService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalDraftService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final long SLOW_SPAN_THRESHOLD_MS = 60_000L;
    private static final double HIGH_COST_THRESHOLD_USD = 0.05D;

    @Resource
    private IAccountService accountService;

    @Resource
    private AgentUsageTelemetryService agentUsageTelemetryService;

    @Resource
    private AdminAuditLogService adminAuditLogService;

    @Resource
    private AgentDebugTraceService agentDebugTraceService;

    @Resource
    private AdminAuthorizationService adminAuthorizationService;

    @Resource
    private ICanvasStateStore canvasStateStore;

    @Resource
    private TraceToEvalIntakeService traceToEvalIntakeService;

    @Resource
    private TraceToEvalDraftService traceToEvalDraftService;

    /** P0 manual entry: metadata-only candidate creation; it neither reads debug payloads nor invokes an LLM. */
    @PostMapping("/runs/{runId}/eval-candidates")
    public Response<EvalCaseCandidate> createEvalCandidate(@PathVariable("runId") String runId,
                                                           HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseCandidate candidate = traceToEvalIntakeService.createManualCandidate(runId, admin.get().getId());
            audit(admin.get(), "CREATE_EVAL_CANDIDATE", "RUN", runId, "SUCCESS", request);
            return success(candidate);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "CREATE_EVAL_CANDIDATE", "RUN", runId, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "CREATE_EVAL_CANDIDATE", "RUN", runId, "ERROR", request);
            return failure("failed to create Eval Candidate");
        }
    }

    @GetMapping("/eval-candidates")
    public Response<List<EvalCaseCandidate>> listEvalCandidates(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "risk", required = false) String risk,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            List<EvalCaseCandidate> candidates = traceToEvalIntakeService.listCandidates(status, risk, limit, offset);
            audit(admin.get(), "LIST_EVAL_CANDIDATES", "EVAL_CANDIDATE", null, "SUCCESS", request);
            return success(candidates);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "LIST_EVAL_CANDIDATES", "EVAL_CANDIDATE", null, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "LIST_EVAL_CANDIDATES", "EVAL_CANDIDATE", null, "ERROR", request);
            return failure("failed to list Eval Candidates");
        }
    }

    @PostMapping("/eval-candidates/{candidateId}/status")
    public Response<EvalCaseCandidate> transitionEvalCandidate(@PathVariable("candidateId") String candidateId,
                                                               @RequestBody Map<String, String> body,
                                                               HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseCandidate candidate = traceToEvalIntakeService.transition(candidateId,
                    body == null ? null : body.get("status"), admin.get().getId(),
                    body == null ? null : body.get("reason"));
            audit(admin.get(), "TRANSITION_EVAL_CANDIDATE", "EVAL_CANDIDATE", candidateId, "SUCCESS", request);
            return success(candidate);
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(admin.get(), "TRANSITION_EVAL_CANDIDATE", "EVAL_CANDIDATE", candidateId, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "TRANSITION_EVAL_CANDIDATE", "EVAL_CANDIDATE", candidateId, "ERROR", request);
            return failure("failed to transition Eval Candidate");
        }
    }

    @PostMapping("/eval-candidates/{candidateId}/draft")
    public Response<TraceToEvalDraftService.Preparation> prepareEvalDraft(@PathVariable("candidateId") String candidateId,
                                                                         @RequestBody Map<String, Object> body,
                                                                         HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            boolean confirmed = body != null && Boolean.TRUE.equals(body.get("purposeConfirmed"));
            TraceToEvalDraftService.Preparation result = traceToEvalDraftService.prepare(candidateId,
                    admin.get().getId(), confirmed, clientIp(request), userAgent(request));
            audit(admin.get(), "PREPARE_EVAL_DRAFT", "EVAL_CANDIDATE", candidateId, result.status().name(), request);
            return success(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(admin.get(), "PREPARE_EVAL_DRAFT", "EVAL_CANDIDATE", candidateId, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "PREPARE_EVAL_DRAFT", "EVAL_CANDIDATE", candidateId, "ERROR", request);
            return failure("failed to prepare Eval Draft");
        }
    }

    @PostMapping("/eval-candidates/{candidateId}/review")
    public Response<EvalCaseCandidate> reviewEvalCandidate(@PathVariable("candidateId") String candidateId,
                                                           @RequestBody Map<String, String> body, HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseCandidate candidate = traceToEvalIntakeService.review(candidateId, body == null ? null : body.get("decision"),
                    admin.get().getId(), body == null ? null : body.get("reason"));
            audit(admin.get(), "REVIEW_EVAL_CANDIDATE", "EVAL_CANDIDATE", candidateId, "SUCCESS", request);
            return success(candidate);
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(admin.get(), "REVIEW_EVAL_CANDIDATE", "EVAL_CANDIDATE", candidateId, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "REVIEW_EVAL_CANDIDATE", "EVAL_CANDIDATE", candidateId, "ERROR", request);
            return failure("failed to review Eval Candidate");
        }
    }

    @PostMapping("/eval-candidates/{candidateId}/publication")
    public Response<EvalCaseLineage> recordEvalPublication(@PathVariable("candidateId") String candidateId,
                                                           @RequestBody Map<String, String> body, HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseLineage lineage = traceToEvalIntakeService.recordPublication(candidateId, body == null ? null : body.get("caseId"),
                    body == null ? null : body.get("datasetVersion"), body == null ? null : body.get("sanitizerVersion"), admin.get().getId());
            audit(admin.get(), "PUBLISH_EVAL_CASE", "EVAL_CASE", lineage.getCaseId(), "SUCCESS", request);
            return success(lineage);
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(admin.get(), "PUBLISH_EVAL_CASE", "EVAL_CANDIDATE", candidateId, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "PUBLISH_EVAL_CASE", "EVAL_CANDIDATE", candidateId, "ERROR", request);
            return failure("failed to record Eval publication");
        }
    }

    @GetMapping("/users")
    public Response<List<AdminUserDTO>> listUsers(HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        audit(admin.get(), "LIST_USERS", "USER", null, "SUCCESS", request);
        return success(accountService.listUsers().stream()
                .map(this::toUserDto)
                .collect(Collectors.toList()));
    }

    @PostMapping("/users/{userId}/disable")
    public Response<AdminUserDTO> disableUser(@PathVariable("userId") String userId,
                                              HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        if (admin.get().getId().equals(userId)) {
            audit(admin.get(), "DISABLE_USER", "USER", userId, "REJECTED", request);
            return failure("admins cannot disable their own account");
        }
        Optional<UserAccount> disabled = accountService.disableUser(userId);
        if (disabled.isEmpty()) {
            audit(admin.get(), "DISABLE_USER", "USER", userId, "NOT_FOUND", request);
            return failure("user not found");
        }
        audit(admin.get(), "DISABLE_USER", "USER", userId, "SUCCESS", request);
        return success(toUserDto(disabled.get()));
    }

    @GetMapping("/usage")
    public Response<AdminUsageDashboardDTO> usage(HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        AdminUsageSummary summary = agentUsageTelemetryService.summarizeGlobal();
        List<UsageDimensionSummary> groups = agentUsageTelemetryService.summarizeByProviderModelCredentialSource();
        audit(admin.get(), "VIEW_USAGE", "USAGE", null, "SUCCESS", request);
        return success(toUsageDashboard(summary, groups));
    }

    @GetMapping("/runs")
    public Response<List<AdminRunMetadataDTO>> listRuns(@RequestParam(value = "status", required = false) String status,
                                                        @RequestParam(value = "userId", required = false) String userId,
                                                        @RequestParam(value = "agentId", required = false) String agentId,
                                                        @RequestParam(value = "limit", required = false) Integer limit,
                                                        @RequestParam(value = "offset", required = false) Integer offset,
                                                        HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        List<AgentRunTelemetry> runs = agentUsageTelemetryService.listRuns(status, userId, agentId, limit, offset);
        audit(admin.get(), "LIST_RUNS", "RUN", null, "SUCCESS", request);
        return success(runs.stream().map(this::toRunMetadataDto).collect(Collectors.toList()));
    }

    @GetMapping("/runs/{runId}")
    public Response<AdminRunDetailDTO> runDetail(@PathVariable("runId") String runId,
                                                 HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        Optional<AgentRunDetail> detail = agentUsageTelemetryService.findRunDetail(runId);
        if (detail.isEmpty()) {
            audit(admin.get(), "VIEW_RUN", "RUN", runId, "NOT_FOUND", request);
            return failure("run not found");
        }
        audit(admin.get(), "VIEW_RUN", "RUN", runId, "SUCCESS", request);
        return success(toRunDetailDto(detail.get()));
    }

    @GetMapping("/runs/{runId}/diagram-trace")
    public Response<AdminDiagramTraceDTO> diagramTrace(@PathVariable("runId") String runId,
                                                       HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        Optional<AgentRunDetail> detail = agentUsageTelemetryService.findRunDetail(runId);
        if (detail.isEmpty()) {
            audit(admin.get(), "VIEW_DIAGRAM_TRACE", "RUN", runId, "NOT_FOUND", request);
            return failure("run not found");
        }
        audit(admin.get(), "VIEW_DIAGRAM_TRACE", "RUN", runId, "SUCCESS", request);
        return success(toDiagramTraceDto(detail.get()));
    }

    @GetMapping("/runs/{runId}/diagram")
    public Response<DiagramCanvasStateResponseDTO> runDiagram(@PathVariable("runId") String runId,
                                                              HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        Optional<AgentRunDetail> detail = agentUsageTelemetryService.findRunDetail(runId);
        if (detail.isEmpty()) {
            audit(admin.get(), "VIEW_RUN_DIAGRAM", "RUN", runId, "NOT_FOUND", request);
            return failure("run not found");
        }
        AgentRunTelemetry run = detail.get().getRun();
        if (run == null || StringUtils.isAnyBlank(run.getUserId(), run.getDiagramId()) || canvasStateStore == null) {
            audit(admin.get(), "VIEW_RUN_DIAGRAM", "RUN", runId, "EMPTY", request);
            return success(null);
        }
        DiagramCanvasStateResponseDTO diagram = canvasStateStore.find(run.getUserId(), run.getDiagramId())
                .map(this::toDiagramCanvasState)
                .orElse(null);
        audit(admin.get(), "VIEW_RUN_DIAGRAM", "DIAGRAM", run.getDiagramId(),
                diagram == null ? "NOT_FOUND" : "SUCCESS", request);
        return success(diagram);
    }

    @GetMapping("/debug-traces/runs/{runId}/captures")
    public Response<List<AdminDebugTraceCaptureDTO>> viewDebugTraceCaptures(@PathVariable("runId") String runId,
                                                                           HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        try {
            List<DebugTraceCapture> captures = agentDebugTraceService.viewCapturesForRun(
                    admin.get().getId(), runId, clientIp(request), userAgent(request));
            return success(captures.stream()
                    .map(this::toDebugTraceCaptureDto)
                    .collect(Collectors.toList()));
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    @GetMapping("/debug-traces/runs/{runId}/spans/{spanId}/payloads")
    public Response<List<AdminDebugTraceCaptureDTO>> viewSpanPayloads(@PathVariable("runId") String runId,
                                                                     @PathVariable("spanId") String spanId,
                                                                     HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        try {
            List<DebugTraceCapture> captures = agentDebugTraceService.viewCapturesForSpan(
                    admin.get().getId(), runId, spanId, clientIp(request), userAgent(request));
            return success(captures.stream().map(this::toDebugTraceCaptureDto).collect(Collectors.toList()));
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    @GetMapping("/audit-logs")
    public Response<List<AdminAuditLogDTO>> auditLogs(@RequestParam(value = "limit", required = false) Integer limit,
                                                      HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        audit(admin.get(), "VIEW_AUDIT_LOGS", "AUDIT_LOG", null, "SUCCESS", request);
        return success(adminAuditLogService.listRecent(limit).stream()
                .map(this::toAuditLogDto)
                .collect(Collectors.toList()));
    }

    @PostMapping("/debug-traces/controls")
    public Response<AdminDebugTraceControlDTO> enableDebugTrace(@RequestBody AdminDebugTraceControlRequestDTO body,
                                                               HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        try {
            DebugTraceControl control = agentDebugTraceService.enableControl(
                    admin.get().getId(),
                    body == null ? null : body.getUserId(),
                    body == null ? null : body.getRunId(),
                    body == null ? null : body.getStartsAt(),
                    body == null ? null : body.getEndsAt());
            audit(admin.get(), "ENABLE_DEBUG_TRACE", "DEBUG_TRACE_CONTROL", control.getId(), "SUCCESS", request);
            return success(toDebugTraceControlDto(control));
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "ENABLE_DEBUG_TRACE", "DEBUG_TRACE_CONTROL", null, "REJECTED", request);
            return failure(e.getMessage());
        }
    }

    @PostMapping("/debug-traces/runs/{runId}/retention")
    public Response<Integer> extendDebugTraceRetention(@PathVariable("runId") String runId,
                                                       @RequestBody AdminDebugTraceRetentionRequestDTO body,
                                                       HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        try {
            int extended = agentDebugTraceService.extendRetentionForRun(
                    admin.get().getId(), runId, body == null ? null : body.getExpiresAt(),
                    clientIp(request), userAgent(request));
            return success(extended);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    @PostMapping("/debug-traces/cleanup")
    public Response<Integer> cleanupDebugTraceContent(HttpServletRequest request) {
        Optional<UserAccount> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return forbidden();
        }
        int cleaned = agentDebugTraceService.cleanupExpiredContent();
        audit(admin.get(), "CLEANUP_DEBUG_TRACE", "DEBUG_TRACE", null, "SUCCESS", request);
        return success(cleaned);
    }

    private Optional<UserAccount> requireAdmin(HttpServletRequest request) {
        return adminAuthorizationService.currentAdmin(request);
    }

    private void audit(UserAccount admin,
                       String action,
                       String targetType,
                       String targetId,
                       String outcome,
                       HttpServletRequest request) {
        adminAuditLogService.record(admin.getId(), action, targetType, targetId, outcome,
                clientIp(request), userAgent(request));
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        // Trust the servlet container's resolved remote address; forwarded headers are spoofable here.
        return StringUtils.trimToNull(request.getRemoteAddr());
    }

    private String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String value = StringUtils.trimToNull(request.getHeader("User-Agent"));
        return value == null ? null : StringUtils.left(value, 256);
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private <T> Response<T> failure(String info) {
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(info)
                .build();
    }

    private <T> Response<T> forbidden() {
        return Response.<T>builder()
                .code(ResponseCode.AUTH_FORBIDDEN.getCode())
                .info(ResponseCode.AUTH_FORBIDDEN.getInfo())
                .build();
    }

    private AdminUserDTO toUserDto(UserAccount user) {
        AdminUserDTO dto = new AdminUserDTO();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setStatus(user.getStatus() == null ? null : user.getStatus().name());
        dto.setSessionVersion(user.getSessionVersion());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());
        dto.setVerifiedAt(user.getVerifiedAt());
        return dto;
    }

    private AdminUsageDashboardDTO toUsageDashboard(AdminUsageSummary summary, List<UsageDimensionSummary> groups) {
        AdminUsageDashboardDTO dto = new AdminUsageDashboardDTO();
        dto.setRequestCount(summary.getRequestCount());
        dto.setSuccessfulRequestCount(summary.getSuccessfulRequestCount());
        dto.setFailedRequestCount(summary.getFailedRequestCount());
        dto.setRunningRequestCount(summary.getRunningRequestCount());
        dto.setRequestSuccessRate(rate(summary.getSuccessfulRequestCount(), summary.getRequestCount()));
        dto.setRequestFailureRate(rate(summary.getFailedRequestCount(), summary.getRequestCount()));
        dto.setLlmCallCount(summary.getLlmCallCount());
        dto.setSuccessfulLlmCallCount(summary.getSuccessfulLlmCallCount());
        dto.setFailedLlmCallCount(summary.getFailedLlmCallCount());
        dto.setToolCallCount(summary.getToolCallCount());
        dto.setSuccessfulToolCallCount(summary.getSuccessfulToolCallCount());
        dto.setFailedToolCallCount(summary.getFailedToolCallCount());
        dto.setPromptTokens(summary.getPromptTokens());
        dto.setCompletionTokens(summary.getCompletionTokens());
        dto.setTotalTokens(summary.getTotalTokens());
        dto.setUnknownTokenLlmCallCount(summary.getUnknownTokenLlmCallCount());
        dto.setAverageRunLatencyMs(summary.getAverageRunLatencyMs());
        dto.setMaxRunLatencyMs(summary.getMaxRunLatencyMs());
        dto.setGroups(groups.stream().map(this::toUsageDimensionDto).collect(Collectors.toList()));
        return dto;
    }

    private Double rate(Long part, Long total) {
        if (total == null || total == 0L || part == null) {
            return 0.0D;
        }
        return part / (double) total;
    }

    private AdminUsageDimensionDTO toUsageDimensionDto(UsageDimensionSummary summary) {
        AdminUsageDimensionDTO dto = new AdminUsageDimensionDTO();
        dto.setProvider(summary.getProvider());
        dto.setModel(summary.getModel());
        dto.setCredentialSource(summary.getCredentialSource());
        dto.setLlmCallCount(summary.getLlmCallCount());
        dto.setSuccessfulCallCount(summary.getSuccessfulCallCount());
        dto.setFailedCallCount(summary.getFailedCallCount());
        dto.setPromptTokens(summary.getPromptTokens());
        dto.setCompletionTokens(summary.getCompletionTokens());
        dto.setTotalTokens(summary.getTotalTokens());
        dto.setUnknownTokenCallCount(summary.getUnknownTokenCallCount());
        dto.setAverageLatencyMs(summary.getAverageLatencyMs());
        return dto;
    }

    private AdminRunDetailDTO toRunDetailDto(AgentRunDetail detail) {
        AdminRunDetailDTO dto = new AdminRunDetailDTO();
        dto.setRun(toRunMetadataDto(detail.getRun()));
        dto.setSteps(safeList(detail.getSteps()).stream().map(this::toRunStepDto).collect(Collectors.toList()));
        dto.setLlmCalls(safeList(detail.getLlmCalls()).stream().map(this::toLlmCallDto).collect(Collectors.toList()));
        dto.setToolCalls(safeList(detail.getToolCalls()).stream().map(this::toToolCallDto).collect(Collectors.toList()));
        dto.setTraceEvents(safeList(detail.getTraceEvents()).stream().map(this::toTraceEventDto).collect(Collectors.toList()));
        dto.setTimeline(toTimeline(detail));
        return dto;
    }

    private AdminDiagramTraceDTO toDiagramTraceDto(AgentRunDetail detail) {
        CanvasState diagramState = currentCanvasState(detail).orElse(null);
        List<AdminDiagramTraceSpanDTO> spans = toDiagramTraceSpans(detail, diagramState);
        AdminDiagramTraceSummaryDTO summary = toDiagramTraceSummary(detail, spans);
        AdminDiagramTraceDTO dto = new AdminDiagramTraceDTO();
        dto.setRun(toRunMetadataDto(detail.getRun()));
        dto.setSpans(spans);
        dto.setSummary(summary);
        List<AdminDiagramSnapshotDTO> snapshots = toPersistedDiagramSnapshots(summary == null ? null : summary.getRunId());
        List<AdminDiagramSnapshotDTO> visibleSnapshots = snapshots.isEmpty()
                ? toDiagramSnapshots(detail, spans, diagramState)
                : snapshots;
        attachDiagramSnapshotDiffs(spans, visibleSnapshots);
        dto.setSnapshots(visibleSnapshots);
        dto.setFindings(toDiagramTraceFindings(detail, spans, diagramState, summary));
        dto.setPayloadAvailability(toPayloadAvailability());
        return dto;
    }

    private AdminDiagramTraceSummaryDTO toDiagramTraceSummary(AgentRunDetail detail,
                                                             List<AdminDiagramTraceSpanDTO> spans) {
        AgentRunTelemetry run = detail.getRun();
        AdminDiagramTraceSummaryDTO dto = new AdminDiagramTraceSummaryDTO();
        if (run == null) {
            return dto;
        }
        dto.setStatus(run.getStatus());
        dto.setOutcome(diagramOutcome(detail));
        dto.setRunId(run.getId());
        dto.setRequestId(run.getRequestId());
        dto.setUserId(run.getUserId());
        dto.setSessionId(run.getSessionId());
        dto.setDiagramId(run.getDiagramId());
        dto.setAgentId(run.getAgentId());
        dto.setRequestType(run.getRequestType());
        dto.setLatencyMs(run.getLatencyMs());
        dto.setLlmCallCount(run.getLlmCallCount());
        dto.setToolCallCount(run.getToolCallCount());
        dto.setEventCount(run.getTraceEventCount());
        dto.setSpanCount((long) safeList(spans).size());
        dto.setTotalTokens(run.getKnownTotalTokens());
        dto.setEstimatedCost(safeList(detail.getLlmCalls()).stream()
                .mapToDouble(call -> estimateCostUsd(call.getPromptTokens(), call.getCompletionTokens(), call.getModel()))
                .sum());
        return dto;
    }

    private String diagramOutcome(AgentRunDetail detail) {
        AgentRunTelemetry run = detail.getRun();
        if (run == null) {
            return "NO_DIAGRAM";
        }
        boolean drawToolUsed = safeList(detail.getToolCalls()).stream()
                .map(ToolCallTelemetry::getToolName)
                .filter(StringUtils::isNotBlank)
                .anyMatch(this::isDiagramMutationTool);
        if (StringUtils.equalsIgnoreCase(run.getStatus(), "FAILED")) {
            return drawToolUsed || StringUtils.isNotBlank(run.getDiagramId())
                    ? "FAILED_AFTER_DRAWING"
                    : "FAILED_BEFORE_DRAWING";
        }
        if (StringUtils.isBlank(run.getDiagramId())) {
            return "NO_DIAGRAM";
        }
        boolean modified = safeList(detail.getToolCalls()).stream()
                .map(ToolCallTelemetry::getToolName)
                .anyMatch(name -> StringUtils.equalsIgnoreCase(name, "modify_diagram"));
        return modified ? "DIAGRAM_MODIFIED" : "DIAGRAM_CREATED";
    }

    private boolean isDiagramMutationTool(String toolName) {
        return StringUtils.equalsIgnoreCase(toolName, "create_diagram")
                || StringUtils.equalsIgnoreCase(toolName, "modify_diagram");
    }

    private List<AdminDiagramTraceSpanDTO> toDiagramTraceSpans(AgentRunDetail detail, CanvasState diagramState) {
        List<AdminDiagramTraceSpanDTO> spans = new ArrayList<>();
        AgentRunTelemetry run = detail.getRun();
        String runId = run == null ? null : run.getId();
        String requestId = run == null ? null : run.getRequestId();
        if (run != null) {
            spans.add(runSpan(run));
        }
        safeList(detail.getTraceEvents()).forEach(event -> spans.add(traceSpan(event, run, diagramState)));
        safeList(detail.getSteps()).forEach(step -> spans.add(traceSpan(step, runId, requestId)));
        safeList(detail.getLlmCalls()).forEach(call -> spans.add(traceSpan(call, runId, requestId)));
        safeList(detail.getToolCalls()).forEach(call -> spans.add(traceSpan(call, run, diagramState)));
        spans.sort(Comparator
                .comparing(AdminDiagramTraceSpanDTO::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(span -> spanKindRank(span.getKind()))
                .thenComparing(span -> span.getSequenceNo() == null ? Long.MAX_VALUE : span.getSequenceNo())
                .thenComparing(AdminDiagramTraceSpanDTO::getId, Comparator.nullsLast(String::compareTo)));
        return spans;
    }

    private AdminDiagramTraceSpanDTO runSpan(AgentRunTelemetry run) {
        AdminDiagramTraceSpanDTO dto = new AdminDiagramTraceSpanDTO();
        dto.setId(run.getId());
        dto.setKind("RUN");
        dto.setName(StringUtils.defaultIfBlank(run.getRequestType(), "run"));
        dto.setRunId(run.getId());
        dto.setRequestId(run.getRequestId());
        dto.setUserId(run.getUserId());
        dto.setPhase("request");
        dto.setStatus(run.getStatus());
        dto.setStartedAt(run.getStartedAt());
        dto.setCompletedAt(run.getCompletedAt());
        dto.setLatencyMs(run.getLatencyMs());
        dto.setErrorClass(run.getErrorClass());
        return dto;
    }

    private AdminDiagramTraceSpanDTO traceSpan(AgentTraceEvent event, AgentRunTelemetry run, CanvasState diagramState) {
        String runId = run == null ? null : run.getId();
        AdminDiagramTraceSpanDTO dto = baseTraceSpan(
                event.getId(), normalizedParentId(event.getParentId(), runId), "EVENT",
                StringUtils.defaultIfBlank(event.getEventType(), "event"), event.getRunId(), event.getRequestId(),
                event.getUserId(), event.getSequenceNo(), event.getEventType(), event.getPhase(), event.getStatus(),
                event.getOccurredAt(), event.getOccurredAt(), null);
        dto.setMetadataJson(event.getMetadataJson());
        if (isDiagramEvent(event.getEventType())) {
            dto.setDiagramEffect(diagramEffect(run, diagramState));
        }
        return dto;
    }

    private AdminDiagramTraceSpanDTO traceSpan(AgentRunStepTelemetry step, String runId, String requestId) {
        AdminDiagramTraceSpanDTO dto = baseTraceSpan(
                step.getId(), normalizedParentId(step.getParentId(), runId), "STEP",
                StringUtils.defaultIfBlank(step.getPhase(), "step"), step.getRunId(), requestId,
                step.getUserId(), null, "STEP", step.getPhase(), step.getStatus(),
                step.getStartedAt(), step.getCompletedAt(), step.getLatencyMs());
        dto.setErrorClass(step.getErrorClass());
        return dto;
    }

    private AdminDiagramTraceSpanDTO traceSpan(LlmCallTelemetry call, String runId, String requestId) {
        String providerModel = StringUtils.defaultString(call.getProvider()) + "/" + StringUtils.defaultString(call.getModel());
        AdminDiagramTraceSpanDTO dto = baseTraceSpan(
                call.getId(), normalizedParentId(call.getParentId(), runId), "LLM",
                providerModel, call.getRunId(), requestId, call.getUserId(), null, "LLM_CALL",
                call.getPhase(), call.getStatus(), call.getStartedAt(), call.getCompletedAt(), call.getLatencyMs());
        dto.setProvider(call.getProvider());
        dto.setModel(call.getModel());
        dto.setPromptTokens(call.getPromptTokens());
        dto.setCompletionTokens(call.getCompletionTokens());
        dto.setTotalTokens(call.getTotalTokens());
        dto.setProviderRequestId(call.getProviderRequestId());
        dto.setProviderResponseId(call.getProviderResponseId());
        dto.setTtftMs(call.getTtftMs());
        dto.setAttemptCount(call.getAttemptCount());
        dto.setRetryCount(call.getRetryCount());
        dto.setEstimatedCost(estimateCostUsd(call.getPromptTokens(), call.getCompletionTokens(), call.getModel()));
        dto.setErrorClass(call.getErrorClass());
        return dto;
    }

    private AdminDiagramTraceSpanDTO traceSpan(ToolCallTelemetry call, AgentRunTelemetry run, CanvasState diagramState) {
        String runId = run == null ? null : run.getId();
        String requestId = run == null ? null : run.getRequestId();
        AdminDiagramTraceSpanDTO dto = baseTraceSpan(
                call.getId(), normalizedParentId(call.getParentId(), runId), "TOOL",
                StringUtils.defaultIfBlank(call.getToolName(), "tool"), call.getRunId(), requestId,
                call.getUserId(), null, "TOOL_CALL", call.getPhase(), call.getStatus(),
                call.getStartedAt(), call.getCompletedAt(), call.getLatencyMs());
        dto.setToolName(call.getToolName());
        dto.setErrorClass(call.getErrorClass());
        if (isDiagramMutationTool(call.getToolName())) {
            dto.setDiagramEffect(diagramEffect(run, diagramState));
        }
        return dto;
    }

    private boolean isDiagramEvent(String eventType) {
        String value = StringUtils.defaultString(eventType).toLowerCase();
        return value.contains("diagram")
                || value.contains("canvas")
                || value.contains("thumbnail")
                || value.contains("render")
                || value.contains("xml");
    }

    private Optional<CanvasState> currentCanvasState(AgentRunDetail detail) {
        AgentRunTelemetry run = detail.getRun();
        if (run == null || canvasStateStore == null || StringUtils.isAnyBlank(run.getUserId(), run.getDiagramId())) {
            return Optional.empty();
        }
        return canvasStateStore.find(run.getUserId(), run.getDiagramId());
    }

    private AdminDiagramEffectDTO diagramEffect(AgentRunTelemetry run, CanvasState state) {
        if (run == null || StringUtils.isBlank(run.getDiagramId())) {
            return null;
        }
        AdminDiagramEffectDTO dto = new AdminDiagramEffectDTO();
        dto.setDiagramId(run.getDiagramId());
        if (state == null) {
            dto.setRenderStatus("DIAGRAM_NOT_FOUND");
            return dto;
        }
        dto.setAfterVersion(state.getVersion());
        dto.setAfterHash(state.getContentHash());
        dto.setThumbnailUrl(state.getThumbnailUrl());
        dto.setRenderStatus(diagramRenderStatus(state));
        return dto;
    }

    private String diagramRenderStatus(CanvasState state) {
        if (state == null) {
            return "DIAGRAM_NOT_FOUND";
        }
        if (StringUtils.isNotBlank(state.getThumbnailUrl())) {
            return "THUMBNAIL_RENDERED";
        }
        if (StringUtils.isNotBlank(state.getCurrentXml())) {
            return "XML_AVAILABLE";
        }
        return "NO_RENDER_EVIDENCE";
    }

    private List<AdminDiagramSnapshotDTO> toDiagramSnapshots(AgentRunDetail detail,
                                                             List<AdminDiagramTraceSpanDTO> spans,
                                                             CanvasState diagramState) {
        AgentRunTelemetry run = detail.getRun();
        if (run == null || diagramState == null) {
            return List.of();
        }
        AdminDiagramSnapshotDTO dto = new AdminDiagramSnapshotDTO();
        dto.setId(currentSnapshotId(run, diagramState));
        dto.setRunId(run.getId());
        dto.setSpanId(latestDiagramSpanId(spans));
        dto.setDiagramId(StringUtils.defaultIfBlank(diagramState.getDiagramId(), run.getDiagramId()));
        dto.setVersion(diagramState.getVersion());
        dto.setCanvasHash(diagramState.getContentHash());
        dto.setThumbnailUrl(diagramState.getThumbnailUrl());
        dto.setSummary(diagramState.getSummary());
        dto.setCreatedAt(snapshotCreatedAt(diagramState, run));
        return List.of(dto);
    }

    private List<AdminDiagramSnapshotDTO> toPersistedDiagramSnapshots(String runId) {
        if (StringUtils.isBlank(runId) || agentUsageTelemetryService == null) {
            return List.of();
        }
        return agentUsageTelemetryService.listDiagramSnapshots(runId).stream()
                .map(this::toDiagramSnapshotDto)
                .collect(Collectors.toList());
    }

    private void attachDiagramSnapshotDiffs(List<AdminDiagramTraceSpanDTO> spans,
                                            List<AdminDiagramSnapshotDTO> snapshots) {
        if (spans == null || spans.isEmpty() || snapshots == null || snapshots.isEmpty()) {
            return;
        }
        List<AdminDiagramSnapshotDTO> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator
                .comparing(AdminDiagramSnapshotDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdminDiagramSnapshotDTO::getVersion, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdminDiagramSnapshotDTO::getId, Comparator.nullsLast(String::compareTo)));
        for (AdminDiagramTraceSpanDTO span : spans) {
            int afterIndex = lastSnapshotIndexForSpan(ordered, span.getId());
            if (afterIndex < 0) {
                continue;
            }
            AdminDiagramSnapshotDTO after = ordered.get(afterIndex);
            AdminDiagramSnapshotDTO before = afterIndex == 0 ? null : ordered.get(afterIndex - 1);
            AdminDiagramEffectDTO effect = span.getDiagramEffect() == null
                    ? new AdminDiagramEffectDTO()
                    : span.getDiagramEffect();
            // Production snapshots are linked to the drawing STEP, so snapshot evidence can create the effect itself.
            effect.setDiagramId(after.getDiagramId());
            effect.setAfterVersion(after.getVersion());
            effect.setAfterHash(after.getCanvasHash());
            effect.setChangedCellCount(after.getChangedCellCount());
            effect.setThumbnailUrl(after.getThumbnailUrl());
            effect.setRenderStatus(StringUtils.isNotBlank(after.getThumbnailUrl())
                    ? "THUMBNAIL_RENDERED"
                    : StringUtils.isNotBlank(after.getCanvasHash()) ? "XML_AVAILABLE" : "NO_RENDER_EVIDENCE");
            if (before != null) {
                effect.setBeforeVersion(before.getVersion());
                effect.setBeforeHash(before.getCanvasHash());
                // Snapshot hashes are the persisted evidence for XML changes; raw XML stays in the protected payload path.
                effect.setXmlChanged(!Objects.equals(before.getCanvasHash(), after.getCanvasHash()));
                effect.setThumbnailChanged(!Objects.equals(before.getThumbnailUrl(), after.getThumbnailUrl()));
            }
            span.setDiagramEffect(effect);
        }
    }

    private int lastSnapshotIndexForSpan(List<AdminDiagramSnapshotDTO> snapshots, String spanId) {
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            if (StringUtils.equals(spanId, snapshots.get(index).getSpanId())) {
                return index;
            }
        }
        return -1;
    }

    private AdminDiagramSnapshotDTO toDiagramSnapshotDto(AgentDiagramTraceSnapshot snapshot) {
        AdminDiagramSnapshotDTO dto = new AdminDiagramSnapshotDTO();
        dto.setId(snapshot.getId());
        dto.setRunId(snapshot.getRunId());
        dto.setSpanId(snapshot.getSpanId());
        dto.setDiagramId(snapshot.getDiagramId());
        dto.setVersion(snapshot.getVersion());
        dto.setCanvasHash(snapshot.getCanvasHash());
        dto.setThumbnailUrl(snapshot.getThumbnailUrl());
        dto.setSummary(snapshot.getSummary());
        dto.setChangedCellCount(snapshot.getChangedCellCount());
        dto.setCreatedAt(snapshot.getCreatedAt());
        return dto;
    }

    private String currentSnapshotId(AgentRunTelemetry run, CanvasState diagramState) {
        String diagramId = StringUtils.defaultIfBlank(diagramState.getDiagramId(), run.getDiagramId());
        String version = diagramState.getVersion() == null ? "current" : "v" + diagramState.getVersion();
        return "ads_current_" + StringUtils.defaultString(run.getId()) + "_" + StringUtils.defaultString(diagramId) + "_" + version;
    }

    private String latestDiagramSpanId(List<AdminDiagramTraceSpanDTO> spans) {
        return safeList(spans).stream()
                .filter(span -> span.getDiagramEffect() != null)
                .max(Comparator.comparing(this::spanSnapshotTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(AdminDiagramTraceSpanDTO::getId)
                .orElse(null);
    }

    private Instant spanSnapshotTime(AdminDiagramTraceSpanDTO span) {
        return span.getCompletedAt() == null ? span.getStartedAt() : span.getCompletedAt();
    }

    private Instant snapshotCreatedAt(CanvasState diagramState, AgentRunTelemetry run) {
        Instant fromUpdatedAt = toInstant(diagramState.getUpdatedAt());
        if (fromUpdatedAt != null) {
            return fromUpdatedAt;
        }
        Instant fromCreatedAt = toInstant(diagramState.getCreatedAt());
        if (fromCreatedAt != null) {
            return fromCreatedAt;
        }
        return run.getCompletedAt() == null ? run.getStartedAt() : run.getCompletedAt();
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private List<AdminDiagramFindingDTO> toDiagramTraceFindings(AgentRunDetail detail,
                                                                List<AdminDiagramTraceSpanDTO> spans,
                                                                CanvasState diagramState,
                                                                AdminDiagramTraceSummaryDTO summary) {
        List<AdminDiagramFindingDTO> findings = new ArrayList<>();
        AgentRunTelemetry run = detail.getRun();
        String diagramId = run == null ? null : run.getDiagramId();
        if (run == null) {
            return findings;
        }

        if (StringUtils.isBlank(diagramId) && isSuccessStatus(run.getStatus())) {
            findings.add(finding("ERROR", "RUN_SUCCESS_BUT_NO_DIAGRAM",
                    "Run succeeded without a diagram",
                    "The request completed successfully, but no diagram id was linked to the run.",
                    run.getId(), null,
                    "Inspect create_diagram / modify_diagram tool output and run metadata persistence."));
        } else if (StringUtils.isNotBlank(diagramId) && diagramState == null) {
            findings.add(finding("WARNING", "DIAGRAM_NOT_LINKED",
                    "Linked diagram state was not found",
                    "The run has a diagram id, but the current canvas state store could not find it.",
                    run.getId(), diagramId,
                    "Verify the run owner id, diagram id, and canvas state persistence path."));
        }

        if (diagramState != null) {
            if (StringUtils.isBlank(diagramState.getCurrentXml())) {
                findings.add(finding("ERROR", "EMPTY_CANVAS",
                        "Canvas XML is empty",
                        "The linked diagram exists, but the saved canvas XML is empty.",
                        run.getId(), diagramId,
                        "Open the diagram save path and check whether the tool returned usable draw.io XML."));
            } else if (!looksLikeDrawioXml(diagramState.getCurrentXml())) {
                findings.add(finding("ERROR", "INVALID_XML",
                        "Canvas XML does not look like draw.io XML",
                        "The saved canvas payload is present, but it is not an mxfile or mxGraphModel document.",
                        run.getId(), diagramId,
                        "Inspect the final tool result and XML validation before saving the canvas."));
            }
            if (StringUtils.isBlank(diagramState.getThumbnailUrl())) {
                findings.add(finding("WARNING", "THUMBNAIL_MISSING",
                        "Thumbnail is missing",
                        "The diagram has saved canvas XML, but no thumbnail URL is available for preview.",
                        run.getId(), diagramId,
                        "Run or inspect thumbnail rendering after canvas save."));
            }
        }

        for (AdminDiagramTraceSpanDTO span : safeList(spans)) {
            if ("LLM".equals(span.getKind()) && isFailureStatus(span.getStatus())) {
                findings.add(finding("ERROR", "LLM_FAILED",
                        "LLM call failed",
                        "An LLM span ended with a failed status.",
                        span.getId(), spanDiagramId(span, diagramId),
                        "Open the selected span and inspect provider/model, latency, and error class."));
            }
            if ("TOOL".equals(span.getKind()) && isFailureStatus(span.getStatus())) {
                findings.add(finding("ERROR", "TOOL_FAILED",
                        "Tool call failed",
                        "A tool span ended with a failed status.",
                        span.getId(), spanDiagramId(span, diagramId),
                        "Inspect tool arguments/results in payload evidence when capture is available."));
            }
            if (span.getLatencyMs() != null && span.getLatencyMs() > SLOW_SPAN_THRESHOLD_MS) {
                findings.add(finding("WARNING", "SLOW_SPAN",
                        "Slow trace span",
                        "This span exceeded the slow-span threshold of 60 seconds.",
                        span.getId(), spanDiagramId(span, diagramId),
                        "Check whether the delay came from provider latency, tool execution, or downstream rendering."));
            }
            if (hasNoCanvasChange(span.getDiagramEffect())) {
                findings.add(finding("WARNING", "NO_CANVAS_CHANGE",
                        "Diagram mutation did not change the canvas",
                        "A diagram-related span reported the same canvas hash before and after execution.",
                        span.getId(), spanDiagramId(span, diagramId),
                        "Inspect the tool result and XML patch output for a no-op mutation."));
            }
        }

        if (summary != null && summary.getEstimatedCost() != null
                && summary.getEstimatedCost() > HIGH_COST_THRESHOLD_USD) {
            findings.add(finding("WARNING", "HIGH_COST",
                    "Estimated cost is high",
                    "The estimated model cost for this run is above the trace warning threshold.",
                    run.getId(), diagramId,
                    "Inspect high-token LLM spans and consider prompt trimming or cheaper routing."));
        }

        safeList(detail.getToolCalls()).stream()
                .filter(call -> StringUtils.equalsIgnoreCase(call.getToolName(), "modify_diagram"))
                .filter(call -> isSuccessStatus(call.getStatus()))
                .filter(call -> !hasSaveEvent(detail))
                .findFirst()
                .ifPresent(call -> findings.add(finding("WARNING", "MODIFY_WITHOUT_SAVE",
                        "Modify tool finished without a save event",
                        "modify_diagram succeeded, but no canvas save event was recorded for the run.",
                        call.getId(), diagramId,
                        "Verify that successful modifications emit CANVAS_SAVED / diagram persistence telemetry.")));
        return findings;
    }

    private boolean looksLikeDrawioXml(String xml) {
        String value = StringUtils.defaultString(xml).toLowerCase();
        return value.contains("<mxfile") || value.contains("<mxgraphmodel");
    }

    private boolean isFailureStatus(String status) {
        return StringUtils.equalsIgnoreCase(status, "FAILED")
                || StringUtils.equalsIgnoreCase(status, "ERROR");
    }

    private boolean isSuccessStatus(String status) {
        return StringUtils.equalsIgnoreCase(status, "SUCCESS");
    }

    private boolean hasNoCanvasChange(AdminDiagramEffectDTO effect) {
        return effect != null
                && StringUtils.isNotBlank(effect.getBeforeHash())
                && StringUtils.equals(effect.getBeforeHash(), effect.getAfterHash());
    }

    private boolean hasSaveEvent(AgentRunDetail detail) {
        return safeList(detail.getTraceEvents()).stream()
                .map(AgentTraceEvent::getEventType)
                .filter(StringUtils::isNotBlank)
                .map(String::toLowerCase)
                .anyMatch(eventType -> eventType.contains("canvas_saved")
                        || eventType.contains("diagram_saved")
                        || eventType.contains("thumbnail_rendered"));
    }

    private String spanDiagramId(AdminDiagramTraceSpanDTO span, String fallback) {
        AdminDiagramEffectDTO effect = span.getDiagramEffect();
        if (effect != null && StringUtils.isNotBlank(effect.getDiagramId())) {
            return effect.getDiagramId();
        }
        return fallback;
    }

    private AdminDiagramFindingDTO finding(String severity,
                                           String code,
                                           String title,
                                           String description,
                                           String spanId,
                                           String diagramId,
                                           String suggestion) {
        AdminDiagramFindingDTO dto = new AdminDiagramFindingDTO();
        dto.setSeverity(severity);
        dto.setCode(code);
        dto.setTitle(title);
        dto.setDescription(description);
        dto.setSpanId(spanId);
        dto.setDiagramId(diagramId);
        dto.setSuggestion(suggestion);
        return dto;
    }

    private AdminDiagramTraceSpanDTO baseTraceSpan(String id,
                                                  String parentId,
                                                  String kind,
                                                  String name,
                                                  String runId,
                                                  String requestId,
                                                  String userId,
                                                  Long sequenceNo,
                                                  String eventType,
                                                  String phase,
                                                  String status,
                                                  Instant startedAt,
                                                  Instant completedAt,
                                                  Long latencyMs) {
        AdminDiagramTraceSpanDTO dto = new AdminDiagramTraceSpanDTO();
        dto.setId(id);
        dto.setParentId(parentId);
        dto.setKind(kind);
        dto.setName(name);
        dto.setRunId(runId);
        dto.setRequestId(requestId);
        dto.setUserId(userId);
        dto.setSequenceNo(sequenceNo);
        dto.setEventType(eventType);
        dto.setPhase(phase);
        dto.setStatus(status);
        dto.setStartedAt(startedAt);
        dto.setCompletedAt(completedAt);
        dto.setLatencyMs(latencyMs);
        return dto;
    }

    private String normalizedParentId(String parentId, String runId) {
        return StringUtils.defaultIfBlank(parentId, runId);
    }

    private int spanKindRank(String kind) {
        return switch (StringUtils.defaultString(kind)) {
            case "RUN" -> 0;
            case "EVENT" -> 1;
            case "STEP" -> 2;
            case "LLM" -> 3;
            case "TOOL" -> 4;
            case "DIAGRAM" -> 5;
            case "QUALITY" -> 6;
            default -> 9;
        };
    }

    private double estimateCostUsd(Integer promptTokens, Integer completionTokens, String model) {
        double[] price = pricePerMillion(model);
        double promptCost = ((promptTokens == null ? 0 : promptTokens) / 1_000_000D) * price[0];
        double completionCost = ((completionTokens == null ? 0 : completionTokens) / 1_000_000D) * price[1];
        return promptCost + completionCost;
    }

    private double[] pricePerMillion(String model) {
        String m = StringUtils.defaultString(model).toLowerCase();
        if (m.contains("gpt-5") || m.contains("gpt5")) {
            return new double[]{1.25D, 10D};
        }
        if (m.contains("gpt-4o") || m.contains("4o-mini")) {
            return new double[]{2.5D, 10D};
        }
        if (m.contains("opus")) {
            return new double[]{15D, 75D};
        }
        if (m.contains("sonnet")) {
            return new double[]{3D, 15D};
        }
        if (m.contains("haiku")) {
            return new double[]{0.8D, 4D};
        }
        if (m.contains("gemini")) {
            return new double[]{1.25D, 5D};
        }
        return new double[]{2D, 8D};
    }

    private AdminPayloadAvailabilityDTO toPayloadAvailability() {
        AdminPayloadAvailabilityDTO dto = new AdminPayloadAvailabilityDTO();
        dto.setOnDemand(true);
        dto.setStatus("ON_DEMAND");
        dto.setNote("Payloads are loaded separately and remain retention-gated.");
        return dto;
    }

    private DiagramCanvasStateResponseDTO toDiagramCanvasState(CanvasState state) {
        if (state == null) {
            return null;
        }
        DiagramCanvasStateResponseDTO dto = new DiagramCanvasStateResponseDTO();
        dto.setDiagramId(state.getDiagramId());
        dto.setUserId(state.getUserId());
        dto.setTitle(state.getTitle());
        dto.setDiagramType(state.getDiagramType());
        dto.setThumbnailUrl(state.getThumbnailUrl());
        dto.setCurrentXml(state.getCurrentXml());
        dto.setContentHash(state.getContentHash());
        dto.setSummary(state.getSummary());
        dto.setVersion(state.getVersion());
        dto.setUpdatedAt(state.getUpdatedAt());
        return dto;
    }

    private AdminRunMetadataDTO toRunMetadataDto(AgentRunTelemetry run) {
        AdminRunMetadataDTO dto = new AdminRunMetadataDTO();
        dto.setId(run.getId());
        dto.setRequestId(run.getRequestId());
        dto.setDiagramId(run.getDiagramId());
        dto.setUserId(run.getUserId());
        dto.setAgentId(run.getAgentId());
        dto.setSessionId(run.getSessionId());
        dto.setRequestType(run.getRequestType());
        dto.setCredentialSource(run.getCredentialSource());
        dto.setModelCredentialId(run.getModelCredentialId());
        dto.setStatus(run.getStatus());
        dto.setErrorClass(run.getErrorClass());
        dto.setStartedAt(run.getStartedAt());
        dto.setCompletedAt(run.getCompletedAt());
        dto.setLatencyMs(run.getLatencyMs());
        dto.setStepCount(run.getStepCount());
        dto.setLlmCallCount(run.getLlmCallCount());
        dto.setToolCallCount(run.getToolCallCount());
        dto.setTraceEventCount(run.getTraceEventCount());
        dto.setKnownTotalTokens(run.getKnownTotalTokens());
        return dto;
    }

    private AdminTraceEventDTO toTraceEventDto(AgentTraceEvent event) {
        AdminTraceEventDTO dto = new AdminTraceEventDTO();
        dto.setId(event.getId());
        dto.setRunId(event.getRunId());
        dto.setParentId(event.getParentId());
        dto.setRequestId(event.getRequestId());
        dto.setUserId(event.getUserId());
        dto.setSequenceNo(event.getSequenceNo());
        dto.setEventType(event.getEventType());
        dto.setPhase(event.getPhase());
        dto.setStatus(event.getStatus());
        dto.setMetadataJson(event.getMetadataJson());
        dto.setOccurredAt(event.getOccurredAt());
        return dto;
    }

    private List<AdminRunTimelineEventDTO> toTimeline(AgentRunDetail detail) {
        List<AdminRunTimelineEventDTO> timeline = new ArrayList<>();
        String requestId = detail.getRun() == null ? null : detail.getRun().getRequestId();
        safeList(detail.getTraceEvents()).forEach(event -> timeline.add(timelineEvent(event)));
        safeList(detail.getSteps()).forEach(step -> timeline.add(timelineEvent(step, requestId)));
        safeList(detail.getLlmCalls()).forEach(call -> timeline.add(timelineEvent(call, requestId)));
        safeList(detail.getToolCalls()).forEach(call -> timeline.add(timelineEvent(call, requestId)));
        timeline.sort(Comparator
                .comparing(AdminRunTimelineEventDTO::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(event -> event.getSequenceNo() == null ? Long.MAX_VALUE : event.getSequenceNo())
                .thenComparing(event -> sourceRank(event.getSource()))
                .thenComparing(AdminRunTimelineEventDTO::getId, Comparator.nullsLast(String::compareTo)));
        return timeline;
    }

    private AdminRunTimelineEventDTO timelineEvent(AgentTraceEvent event) {
        AdminRunTimelineEventDTO dto = baseTimelineEvent(
                event.getId(), "trace_event", event.getRunId(), event.getRequestId(), event.getUserId(),
                event.getSequenceNo(), event.getEventType(), event.getPhase(), event.getStatus(), event.getOccurredAt());
        dto.setParentId(event.getParentId());
        dto.setDetail(event.getEventType());
        dto.setMetadataJson(event.getMetadataJson());
        return dto;
    }

    private AdminRunTimelineEventDTO timelineEvent(AgentRunStepTelemetry step, String requestId) {
        AdminRunTimelineEventDTO dto = baseTimelineEvent(
                step.getId(), "step", step.getRunId(), requestId, step.getUserId(),
                null, "STEP", step.getPhase(), step.getStatus(), step.getStartedAt());
        dto.setParentId(step.getParentId());
        dto.setDetail(step.getPhase());
        dto.setLatencyMs(step.getLatencyMs());
        return dto;
    }

    private AdminRunTimelineEventDTO timelineEvent(LlmCallTelemetry call, String requestId) {
        AdminRunTimelineEventDTO dto = baseTimelineEvent(
                call.getId(), "llm_call", call.getRunId(), requestId, call.getUserId(),
                null, "LLM_CALL", call.getPhase(), call.getStatus(), call.getStartedAt());
        dto.setParentId(call.getParentId());
        dto.setDetail(StringUtils.defaultString(call.getProvider()) + "/" + StringUtils.defaultString(call.getModel()));
        dto.setLatencyMs(call.getLatencyMs());
        return dto;
    }

    private AdminRunTimelineEventDTO timelineEvent(ToolCallTelemetry call, String requestId) {
        AdminRunTimelineEventDTO dto = baseTimelineEvent(
                call.getId(), "tool_call", call.getRunId(), requestId, call.getUserId(),
                null, "TOOL_CALL", call.getPhase(), call.getStatus(), call.getStartedAt());
        dto.setParentId(call.getParentId());
        dto.setDetail(call.getToolName());
        dto.setLatencyMs(call.getLatencyMs());
        return dto;
    }

    private AdminRunTimelineEventDTO baseTimelineEvent(String id,
                                                       String source,
                                                       String runId,
                                                       String requestId,
                                                       String userId,
                                                       Long sequenceNo,
                                                       String eventType,
                                                       String phase,
                                                       String status,
                                                       Instant occurredAt) {
        AdminRunTimelineEventDTO dto = new AdminRunTimelineEventDTO();
        dto.setId(id);
        dto.setSource(source);
        dto.setRunId(runId);
        dto.setRequestId(requestId);
        dto.setUserId(userId);
        dto.setSequenceNo(sequenceNo);
        dto.setEventType(eventType);
        dto.setPhase(phase);
        dto.setStatus(status);
        dto.setOccurredAt(occurredAt);
        return dto;
    }

    private int sourceRank(String source) {
        return switch (StringUtils.defaultString(source)) {
            case "trace_event" -> 0;
            case "step" -> 1;
            case "llm_call" -> 2;
            case "tool_call" -> 3;
            default -> 9;
        };
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private AdminRunStepDTO toRunStepDto(AgentRunStepTelemetry step) {
        AdminRunStepDTO dto = new AdminRunStepDTO();
        dto.setId(step.getId());
        dto.setRunId(step.getRunId());
        dto.setParentId(step.getParentId());
        dto.setUserId(step.getUserId());
        dto.setPhase(step.getPhase());
        dto.setStatus(step.getStatus());
        dto.setErrorClass(step.getErrorClass());
        dto.setStartedAt(step.getStartedAt());
        dto.setCompletedAt(step.getCompletedAt());
        dto.setLatencyMs(step.getLatencyMs());
        return dto;
    }

    private AdminLlmCallDTO toLlmCallDto(LlmCallTelemetry call) {
        AdminLlmCallDTO dto = new AdminLlmCallDTO();
        dto.setId(call.getId());
        dto.setRunId(call.getRunId());
        dto.setParentId(call.getParentId());
        dto.setUserId(call.getUserId());
        dto.setPhase(call.getPhase());
        dto.setProvider(call.getProvider());
        dto.setModel(call.getModel());
        dto.setCredentialSource(call.getCredentialSource());
        dto.setModelCredentialId(call.getModelCredentialId());
        dto.setPromptTokens(call.getPromptTokens());
        dto.setCompletionTokens(call.getCompletionTokens());
        dto.setTotalTokens(call.getTotalTokens());
        dto.setProviderRequestId(call.getProviderRequestId());
        dto.setProviderResponseId(call.getProviderResponseId());
        dto.setTtftMs(call.getTtftMs());
        dto.setAttemptCount(call.getAttemptCount());
        dto.setRetryCount(call.getRetryCount());
        dto.setStatus(call.getStatus());
        dto.setErrorClass(call.getErrorClass());
        dto.setStartedAt(call.getStartedAt());
        dto.setCompletedAt(call.getCompletedAt());
        dto.setLatencyMs(call.getLatencyMs());
        return dto;
    }

    private AdminToolCallDTO toToolCallDto(ToolCallTelemetry call) {
        AdminToolCallDTO dto = new AdminToolCallDTO();
        dto.setId(call.getId());
        dto.setRunId(call.getRunId());
        dto.setParentId(call.getParentId());
        dto.setUserId(call.getUserId());
        dto.setPhase(call.getPhase());
        dto.setToolName(call.getToolName());
        dto.setStatus(call.getStatus());
        dto.setErrorClass(call.getErrorClass());
        dto.setStartedAt(call.getStartedAt());
        dto.setCompletedAt(call.getCompletedAt());
        dto.setLatencyMs(call.getLatencyMs());
        return dto;
    }

    private AdminAuditLogDTO toAuditLogDto(AdminAuditLog log) {
        AdminAuditLogDTO dto = new AdminAuditLogDTO();
        dto.setId(log.getId());
        dto.setActorUserId(log.getActorUserId());
        dto.setAction(log.getAction());
        dto.setTargetType(log.getTargetType());
        dto.setTargetId(log.getTargetId());
        dto.setOutcome(log.getOutcome());
        dto.setIpAddress(log.getIpAddress());
        dto.setUserAgent(log.getUserAgent());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }

    private AdminDebugTraceControlDTO toDebugTraceControlDto(DebugTraceControl control) {
        AdminDebugTraceControlDTO dto = new AdminDebugTraceControlDTO();
        dto.setId(control.getId());
        dto.setScopeUserId(control.getScopeUserId());
        dto.setScopeRunId(control.getScopeRunId());
        dto.setScopeStartsAt(control.getScopeStartsAt());
        dto.setScopeEndsAt(control.getScopeEndsAt());
        dto.setEnabled(control.isEnabled());
        dto.setCreatedAt(control.getCreatedAt());
        return dto;
    }

    private AdminDebugTraceCaptureDTO toDebugTraceCaptureDto(DebugTraceCapture capture) {
        AdminDebugTraceCaptureDTO dto = new AdminDebugTraceCaptureDTO();
        dto.setId(capture.getId());
        dto.setControlId(capture.getControlId());
        dto.setUserId(capture.getUserId());
        dto.setRunId(capture.getRunId());
        dto.setSpanId(capture.getSpanId());
        dto.setEventType(capture.getEventType());
        dto.setPayloadKind(capture.getPayloadKind());
        dto.setContentType(capture.getContentType());
        dto.setContent(capture.getContent());
        dto.setContentSha256(capture.getContentSha256());
        dto.setOriginalLength(capture.getOriginalLength());
        dto.setTruncated(capture.isTruncated());
        dto.setContentExpiresAt(capture.getContentExpiresAt());
        dto.setContentDeletedAt(capture.getContentDeletedAt());
        dto.setCreatedAt(capture.getCreatedAt());
        return dto;
    }
}
