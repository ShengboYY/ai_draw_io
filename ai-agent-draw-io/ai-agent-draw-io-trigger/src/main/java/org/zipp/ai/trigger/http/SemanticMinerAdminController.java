package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticSamplingPolicy;
import org.zipp.ai.domain.agent.service.evaluation.intake.SemanticAnomalyDiscoveryService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.List;
import java.util.Optional;

/** Dedicated operations API for semantic scans; production Agent endpoints never depend on this controller. */
@RestController
@RequestMapping("/api/v1/admin/semantic-miner-runs")
public class SemanticMinerAdminController {
    private static final String TARGET_TYPE = "SEMANTIC_MINER_RUN";

    private final SemanticAnomalyDiscoveryService service;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public SemanticMinerAdminController(SemanticAnomalyDiscoveryService service,
                                        AdminAuthorizationService authorization,
                                        AdminAuditLogService audits) {
        this.service = service;
        this.authorization = authorization;
        this.audits = audits;
    }

    @PostMapping
    public Response<SemanticMinerRun> start(@RequestBody StartRequest body, HttpServletRequest request) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            if (body == null) throw new IllegalArgumentException("semantic discovery request is required");
            SemanticSamplingPolicy policy = SemanticSamplingPolicy.valueOf(
                    StringUtils.upperCase(StringUtils.trimToEmpty(body.getSamplingPolicy())));
            SemanticMinerRun run = service.start(policy, body.getLimit(), admin.get().getId(),
                    body.isPurposeConfirmed(), clientIp(request), userAgent(request));
            audit(admin.get(), "START_SEMANTIC_MINER_RUN", run.getId(), run.getStatus().name(), request);
            return success(run);
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(admin.get(), "START_SEMANTIC_MINER_RUN", null, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "START_SEMANTIC_MINER_RUN", null, "ERROR", request);
            return failure("failed to start semantic discovery");
        }
    }

    @GetMapping
    public Response<List<SemanticMinerRun>> list(@RequestParam(defaultValue = "20") int limit,
                                                  HttpServletRequest request) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            List<SemanticMinerRun> runs = service.list(limit);
            audit(admin.get(), "LIST_SEMANTIC_MINER_RUNS", null, "SUCCESS", request);
            return success(runs);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "LIST_SEMANTIC_MINER_RUNS", null, "REJECTED", request);
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "LIST_SEMANTIC_MINER_RUNS", null, "ERROR", request);
            return failure("failed to list semantic discovery runs");
        }
    }

    private void audit(UserAccount admin, String action, String targetId, String outcome, HttpServletRequest request) {
        audits.record(admin.getId(), action, TARGET_TYPE, targetId, outcome, clientIp(request), userAgent(request));
    }

    private String clientIp(HttpServletRequest request) {
        return request == null ? null : StringUtils.trimToNull(request.getRemoteAddr());
    }

    private String userAgent(HttpServletRequest request) {
        String value = request == null ? null : StringUtils.trimToNull(request.getHeader("User-Agent"));
        return value == null ? null : StringUtils.left(value, 256);
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(String info) {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(info).build();
    }

    private <T> Response<T> forbidden() {
        return Response.<T>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode()).info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build();
    }

    @Data
    public static class StartRequest {
        private String samplingPolicy = "TARGETED";
        private int limit = 20;
        private boolean purposeConfirmed;
    }
}
