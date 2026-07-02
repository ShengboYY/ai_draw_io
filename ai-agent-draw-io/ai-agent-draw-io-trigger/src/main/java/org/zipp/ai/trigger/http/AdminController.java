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
import org.zipp.ai.api.dto.AdminDebugTraceControlDTO;
import org.zipp.ai.api.dto.AdminDebugTraceControlRequestDTO;
import org.zipp.ai.api.dto.AdminDebugTraceRetentionRequestDTO;
import org.zipp.ai.api.dto.AdminLlmCallDTO;
import org.zipp.ai.api.dto.AdminRunDetailDTO;
import org.zipp.ai.api.dto.AdminRunMetadataDTO;
import org.zipp.ai.api.dto.AdminRunStepDTO;
import org.zipp.ai.api.dto.AdminToolCallDTO;
import org.zipp.ai.api.dto.AdminUsageDashboardDTO;
import org.zipp.ai.api.dto.AdminUsageDimensionDTO;
import org.zipp.ai.api.dto.AdminUserDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

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
        dto.setSteps(detail.getSteps().stream().map(this::toRunStepDto).collect(Collectors.toList()));
        dto.setLlmCalls(detail.getLlmCalls().stream().map(this::toLlmCallDto).collect(Collectors.toList()));
        dto.setToolCalls(detail.getToolCalls().stream().map(this::toToolCallDto).collect(Collectors.toList()));
        return dto;
    }

    private AdminRunMetadataDTO toRunMetadataDto(AgentRunTelemetry run) {
        AdminRunMetadataDTO dto = new AdminRunMetadataDTO();
        dto.setId(run.getId());
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
        return dto;
    }

    private AdminRunStepDTO toRunStepDto(AgentRunStepTelemetry step) {
        AdminRunStepDTO dto = new AdminRunStepDTO();
        dto.setId(step.getId());
        dto.setRunId(step.getRunId());
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
        dto.setUserId(call.getUserId());
        dto.setPhase(call.getPhase());
        dto.setProvider(call.getProvider());
        dto.setModel(call.getModel());
        dto.setCredentialSource(call.getCredentialSource());
        dto.setModelCredentialId(call.getModelCredentialId());
        dto.setPromptTokens(call.getPromptTokens());
        dto.setCompletionTokens(call.getCompletionTokens());
        dto.setTotalTokens(call.getTotalTokens());
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
}
