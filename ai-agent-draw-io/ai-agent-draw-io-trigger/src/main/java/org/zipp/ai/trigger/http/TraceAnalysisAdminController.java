package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJob;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJobView;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingView;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCasePromotionResult;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCasePromotionService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneException;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceAnalysisJobService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceFindingViewService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalIntakeService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Unified admin API for persistent Trace Analysis jobs and Finding projections. */
@RestController
@RequestMapping("/api/v1/admin")
public class TraceAnalysisAdminController {
    private final TraceAnalysisJobService jobs;
    private final TraceFindingViewService findings;
    private final TraceToEvalIntakeService candidates;
    private final EvalCasePromotionService promotions;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public TraceAnalysisAdminController(TraceAnalysisJobService jobs, TraceFindingViewService findings,
                                        TraceToEvalIntakeService candidates, EvalCasePromotionService promotions,
                                        AdminAuthorizationService authorization,
                                        AdminAuditLogService audits) {
        this.jobs = jobs; this.findings = findings; this.candidates = candidates;
        this.promotions = promotions;
        this.authorization = authorization; this.audits = audits;
    }

    @PostMapping("/traces/{runId}/analysis-jobs")
    public Response<TraceAnalysisJobView> startSingle(@PathVariable("runId") String runId, @RequestBody StartRequest body,
                                                       HttpServletRequest request) {
        return execute(request, "START_TRACE_ANALYSIS", runId, admin -> jobs.startSingle(runId,
                body == null ? null : body.getAnalyzerType(), admin.getId(), body != null && body.isPurposeConfirmed(),
                clientIp(request), userAgent(request)));
    }

    @PostMapping("/trace-analysis-jobs")
    public Response<TraceAnalysisJobView> startBatch(@RequestBody StartRequest body, HttpServletRequest request) {
        return execute(request, "START_TRACE_ANALYSIS_BATCH", null, admin -> jobs.startBatch(
                body == null ? null : body.getAnalyzerType(), body == null ? null : body.getSamplingPolicy(),
                body == null ? 0 : body.getLimit(), body == null ? null : body.getTraceSnapshotAt(), admin.getId(),
                body != null && body.isPurposeConfirmed(), clientIp(request), userAgent(request)));
    }

    @GetMapping("/trace-analysis-jobs/{jobId}")
    public Response<TraceAnalysisJobView> getJob(@PathVariable("jobId") String jobId, HttpServletRequest request) {
        return execute(request, "VIEW_TRACE_ANALYSIS_JOB", jobId, ignored -> jobs.find(jobId));
    }

    @GetMapping("/trace-analysis-jobs")
    public Response<List<TraceAnalysisJob>> listJobs(@RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        return execute(request, "LIST_TRACE_ANALYSIS_JOBS", null, ignored -> jobs.list(limit));
    }

    @GetMapping("/trace-findings")
    public Response<List<TraceFindingView>> listFindings(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String risk,
                                                         @RequestParam(required = false) String analyzer,
                                                         @RequestParam(required = false) String routeType,
                                                         @RequestParam(required = false) String agentId,
                                                         @RequestParam(required = false) String sourceRunId,
                                                         @RequestParam(required = false) Instant discoveredFrom,
                                                         @RequestParam(required = false) Instant discoveredTo,
                                                         @RequestParam(required = false) Long minLatencyMs,
                                                         @RequestParam(required = false) Long maxLatencyMs,
                                                         @RequestParam(defaultValue = "100") int limit,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         HttpServletRequest request) {
        return execute(request, "LIST_TRACE_FINDINGS", null,
                ignored -> findings.list(status, risk, analyzer, routeType, agentId, sourceRunId,
                        discoveredFrom, discoveredTo, minLatencyMs, maxLatencyMs, limit, offset));
    }

    @GetMapping("/trace-findings/{findingId}")
    public Response<TraceFindingView> getFinding(@PathVariable("findingId") String findingId, HttpServletRequest request) {
        return execute(request, "VIEW_TRACE_FINDING", findingId, ignored -> findings.find(findingId));
    }

    @PostMapping("/trace-findings/{findingId}/triage")
    public Response<EvalCaseCandidate> triage(@PathVariable("findingId") String findingId, @RequestBody(required = false) Map<String, String> body,
                                              HttpServletRequest request) {
        return execute(request, "TRIAGE_TRACE_FINDING", findingId, admin -> candidates.transition(findingId,
                "TRIAGED", admin.getId(), body == null ? null : body.get("reason")));
    }

    @PostMapping("/trace-findings/{findingId}/dismiss")
    public Response<EvalCaseCandidate> dismiss(@PathVariable("findingId") String findingId, @RequestBody(required = false) Map<String, String> body,
                                               HttpServletRequest request) {
        return execute(request, "DISMISS_TRACE_FINDING", findingId, admin -> candidates.transition(findingId,
                "REJECTED", admin.getId(), body == null ? null : body.get("reason")));
    }

    @PostMapping("/trace-findings/{findingId}/promote-to-eval-draft")
    public Response<EvalCasePromotionResult> promote(@PathVariable("findingId") String findingId,
                                                      @RequestBody PromoteRequest body,
                                                      HttpServletRequest request) {
        return execute(request, "PROMOTE_TRACE_FINDING", findingId, admin -> promotions.promote(findingId,
                body == null ? null : body.getCaseId(), body == null ? null : body.getCaseVersion(),
                admin.getId(), authorization.evaluationRole(admin)));
    }

    private <T> Response<T> execute(HttpServletRequest request, String action, String targetId,
                                    java.util.function.Function<UserAccount, T> operation) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return response(ResponseCode.AUTH_FORBIDDEN.getCode(), ResponseCode.AUTH_FORBIDDEN.getInfo(), null);
        try {
            T result = operation.apply(admin.get()); audit(admin.get(), action, targetId, "SUCCESS", request);
            return response(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), result);
        } catch (EvalControlPlaneException error) {
            audit(admin.get(), action, targetId, "REJECTED", request);
            return response(error.getCode().name(), error.getMessage(), null);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            audit(admin.get(), action, targetId, "REJECTED", request); return response(ResponseCode.UN_ERROR.getCode(), error.getMessage(), null);
        } catch (RuntimeException error) {
            audit(admin.get(), action, targetId, "ERROR", request); return response(ResponseCode.UN_ERROR.getCode(), "trace analysis operation failed", null);
        }
    }

    private void audit(UserAccount admin, String action, String targetId, String outcome, HttpServletRequest request) {
        audits.record(admin.getId(), action, "TRACE_ANALYSIS", targetId, outcome, clientIp(request), userAgent(request));
    }
    private String clientIp(HttpServletRequest request) { return request == null ? null : StringUtils.trimToNull(request.getRemoteAddr()); }
    private String userAgent(HttpServletRequest request) { return request == null ? null : StringUtils.left(request.getHeader("User-Agent"), 256); }
    private <T> Response<T> response(String code, String info, T data) { return Response.<T>builder().code(code).info(info).data(data).build(); }

    @Data public static class StartRequest {
        private String analyzerType = "DETERMINISTIC";
        private String samplingPolicy = "TARGETED";
        private int limit = 20;
        private Instant traceSnapshotAt;
        private boolean purposeConfirmed;
    }

    @Data public static class PromoteRequest {
        private String caseId;
        private String caseVersion;
    }
}
