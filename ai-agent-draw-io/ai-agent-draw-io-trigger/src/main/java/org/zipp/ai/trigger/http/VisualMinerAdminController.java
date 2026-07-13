package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.service.evaluation.intake.VisualAnomalyDiscoveryService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.Optional;

/** Explicit admin boundary for production pixel access; every attempt is audited. */
@RestController
@RequestMapping("/api/v1/admin/visual-miner")
public class VisualMinerAdminController {
    private final VisualAnomalyDiscoveryService service;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public VisualMinerAdminController(VisualAnomalyDiscoveryService service, AdminAuthorizationService authorization,
                                      AdminAuditLogService audits) {
        this.service = service; this.authorization = authorization; this.audits = audits;
    }

    @PostMapping("/analyze-run")
    public Response<VisualAnomalyDiscoveryService.Result> analyze(@RequestBody AnalyzeRequest body,
                                                                   HttpServletRequest request) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return response(ResponseCode.AUTH_FORBIDDEN.getCode(), ResponseCode.AUTH_FORBIDDEN.getInfo(), null);
        String runId = body == null ? null : body.getSourceRunId();
        try {
            VisualAnomalyDiscoveryService.Result result = service.analyzeRun(runId, admin.get().getId(),
                    body != null && body.isPurposeConfirmed());
            audit(admin.get(), runId, result.status(), request);
            return response(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), result);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            audit(admin.get(), runId, "REJECTED", request);
            return response(ResponseCode.UN_ERROR.getCode(), e.getMessage(), null);
        } catch (RuntimeException e) {
            audit(admin.get(), runId, "ERROR", request);
            return response(ResponseCode.UN_ERROR.getCode(), "visual discovery failed", null);
        }
    }

    private void audit(UserAccount admin, String runId, String outcome, HttpServletRequest request) {
        audits.record(admin.getId(), "ANALYZE_VISUAL_RUN", "AGENT_RUN", runId, outcome,
                request == null ? null : request.getRemoteAddr(), request == null ? null : StringUtils.left(request.getHeader("User-Agent"), 256));
    }
    private <T> Response<T> response(String code, String info, T data) { return Response.<T>builder().code(code).info(info).data(data).build(); }
    @Data public static class AnalyzeRequest { private String sourceRunId; private boolean purposeConfirmed; }
}
