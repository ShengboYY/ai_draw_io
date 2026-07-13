package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCanaryAssessment;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseHealthRecord;
import org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCanaryOperationsService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseHealthOperationsService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.List;
import java.util.Optional;

/** Aggregate-only continuous Evaluation operations. It never executes deployment or rollback actions. */
@RestController
@RequestMapping("/api/v1/admin/eval-operations")
@Slf4j
public class EvaluationOperationsAdminController {
    private final EvalCanaryOperationsService canary;
    private final EvalCaseHealthOperationsService health;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;
    public EvaluationOperationsAdminController(EvalCanaryOperationsService canary, EvalCaseHealthOperationsService health,
            AdminAuthorizationService authorization, AdminAuditLogService audits) {
        this.canary = canary; this.health = health; this.authorization = authorization; this.audits = audits;
    }

    @PostMapping("/canary-assessments")
    public Response<EvalCanaryAssessment> assess(@RequestBody CanaryRequest body, HttpServletRequest request) {
        return execute(request, "ASSESS_EVAL_CANARY", body == null ? null : body.getEvalRunId(), true, admin -> {
            if (body == null) throw new IllegalArgumentException("canary assessment request is required");
            EvalCanaryAssessment assessment = canary.assess(body.getEvalRunId(), body.getDeploymentRef(), body.getPolicyVersion(),
                    window(body.getBaseline()), window(body.getCanary()), policy(body.getPolicy()), admin.getId());
            // Deployment monitoring routes this aggregate-only event to the operator alert channel.
            if (assessment.getOutcome() != EvalCanaryService.Outcome.CONTINUE) log.warn(
                    "[eval-canary-recommendation] outcome={} evalRunId={} deploymentRef={} reasons={}",
                    assessment.getOutcome(), assessment.getEvalRunId(), assessment.getDeploymentRef(), assessment.getReasons());
            return assessment;
        });
    }

    @GetMapping("/canary-assessments")
    public Response<List<EvalCanaryAssessment>> canaryAssessments(@RequestParam String evalRunId,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        return execute(request, "LIST_EVAL_CANARY", evalRunId, false, admin -> canary.list(evalRunId, limit));
    }

    @PostMapping("/case-health/refresh")
    public Response<List<EvalCaseHealthRecord>> refreshHealth(@RequestParam(defaultValue = "200") int runLimit,
            HttpServletRequest request) {
        return execute(request, "REFRESH_EVAL_CASE_HEALTH", null, false, admin -> health.refresh(runLimit));
    }

    @GetMapping("/case-health")
    public Response<List<EvalCaseHealthRecord>> caseHealth(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        return execute(request, "LIST_EVAL_CASE_HEALTH", null, false, admin -> health.list(status, limit));
    }

    private EvalCanaryService.Window window(Window value) { return value == null ? null : new EvalCanaryService.Window(value.getRequests(), value.getFailures(), value.getCriticalFindings(), value.getInfrastructureErrors(), value.getP95LatencyMs(), value.getAverageCost()); }
    private EvalCanaryService.Policy policy(Policy value) { if (value == null) return null; return new EvalCanaryService.Policy(value.getMinimumRequests(), value.getMaximumFailureRateIncrease(), value.getMaximumLatencyRatio(), value.getMaximumCostRatio(), value.getMaximumInfrastructureErrorRate()); }
    private <T> Response<T> execute(HttpServletRequest request, String action, String target, boolean releaseOwner, Operation<T> operation) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return response(ResponseCode.AUTH_FORBIDDEN.getCode(), ResponseCode.AUTH_FORBIDDEN.getInfo(), null);
        try {
            if (releaseOwner && !authorization.isReleaseOwner(admin.get())) throw new SecurityException("Release Owner role is required");
            T value = operation.run(admin.get()); audit(admin.get(), action, target, "SUCCESS", request);
            return response(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), value);
        } catch (SecurityException e) { audit(admin.get(), action, target, "REJECTED", request); return response(ResponseCode.AUTH_FORBIDDEN.getCode(), e.getMessage(), null); }
        catch (IllegalArgumentException | IllegalStateException e) { audit(admin.get(), action, target, "REJECTED", request); return response(ResponseCode.UN_ERROR.getCode(), e.getMessage(), null); }
        catch (RuntimeException e) { audit(admin.get(), action, target, "ERROR", request); return response(ResponseCode.UN_ERROR.getCode(), "Evaluation operations failed", null); }
    }
    private void audit(UserAccount admin, String action, String target, String outcome, HttpServletRequest request) { audits.record(admin.getId(), action, "EVAL_OPERATIONS", target, outcome, request == null ? null : request.getRemoteAddr(), request == null ? null : request.getHeader("User-Agent")); }
    private <T> Response<T> response(String code, String info, T data) { return Response.<T>builder().code(code).info(info).data(data).build(); }
    private interface Operation<T> { T run(UserAccount user); }
    @Data public static class CanaryRequest { private String evalRunId; private String deploymentRef; private String policyVersion = "canary-policy-v1"; private Window baseline; private Window canary; private Policy policy; }
    @Data public static class Window { private int requests; private int failures; private int criticalFindings; private int infrastructureErrors; private double p95LatencyMs; private double averageCost; }
    @Data public static class Policy { private int minimumRequests = 100; private double maximumFailureRateIncrease = 0.02D; private double maximumLatencyRatio = 1.2D; private double maximumCostRatio = 1.2D; private double maximumInfrastructureErrorRate = 0.05D; }
}
