package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalGateOutcome;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneErrorCode;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalTargetGateCompositionService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Release-owner API used by both Operations UI and CI; it never starts or mutates a Run. */
@RestController
@RequestMapping("/api/v1/admin/eval-release-gates")
public class EvaluationReleaseGateAdminController {
    private final EvalTargetGateCompositionService composition;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public EvaluationReleaseGateAdminController(EvalTargetGateCompositionService composition,
                                                AdminAuthorizationService authorization,
                                                AdminAuditLogService audits) {
        this.composition = composition; this.authorization = authorization; this.audits = audits;
    }

    @PostMapping("/compose")
    public Response<EvalTargetGateCompositionService.Decision> compose(@RequestBody ComposeRequest body,
                                                                        HttpServletRequest request) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return response(ResponseCode.AUTH_FORBIDDEN.getCode(), ResponseCode.AUTH_FORBIDDEN.getInfo(), null);
        try {
            if (!authorization.isReleaseOwner(admin.get())) throw new SecurityException("Release Owner role is required");
            if (body == null) throw new IllegalArgumentException("Gate composition request is required");
            EvalTargetGateCompositionService.Decision result = composition.compose(body.parsedRuns(), body.parsedRequired());
            audit(admin.get(), "SUCCESS", request);
            // Optional target degradation is non-blocking, but it remains independently auditable.
            result.targets().values().stream()
                    .filter(target -> !target.required() && target.outcome() != EvalGateOutcome.PASS)
                    .forEach(target -> audits.record(admin.get().getId(), "EVAL_RELEASE_GATE_OPTIONAL_WARNING",
                            "EVAL_RELEASE_GATE", target.target() + ":" + target.outcome(), "WARNING",
                            request == null ? null : request.getRemoteAddr(), request == null ? null : request.getHeader("User-Agent")));
            return response(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), result);
        } catch (SecurityException e) {
            audit(admin.get(), "REJECTED", request);
            return response(ResponseCode.AUTH_FORBIDDEN.getCode(), e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "REJECTED", request);
            return response(EvalControlPlaneErrorCode.VALIDATION_FAILED.name(), e.getMessage(), null);
        } catch (RuntimeException e) {
            audit(admin.get(), "ERROR", request);
            return response(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR.name(), "Release Gate composition failed", null);
        }
    }

    private void audit(UserAccount admin, String outcome, HttpServletRequest request) {
        audits.record(admin.getId(), "COMPOSE_EVAL_RELEASE_GATE", "EVAL_RELEASE_GATE", null, outcome,
                request == null ? null : request.getRemoteAddr(), request == null ? null : request.getHeader("User-Agent"));
    }

    private <T> Response<T> response(String code, String info, T data) {
        return Response.<T>builder().code(code).info(info).data(data).build();
    }

    @Data
    public static class ComposeRequest {
        private Map<String, String> runIds = Map.of();
        private Set<String> requiredTargets = Set.of();

        Map<EvaluationTarget, String> parsedRuns() {
            Map<EvaluationTarget, String> values = new EnumMap<>(EvaluationTarget.class);
            if (runIds != null) runIds.forEach((target, runId) -> values.put(EvaluationTarget.valueOf(target), runId));
            return values;
        }

        Set<EvaluationTarget> parsedRequired() {
            Set<EvaluationTarget> values = EnumSet.noneOf(EvaluationTarget.class);
            if (requiredTargets != null) requiredTargets.forEach(target -> values.add(EvaluationTarget.valueOf(target)));
            return values;
        }
    }
}
