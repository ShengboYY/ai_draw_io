package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalStatisticalReport;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneAuditTypes;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneErrorCode;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneException;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalRunOrchestrator;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalRunQueryService;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.List;
import java.util.Optional;

/** Admin API for immutable Mode B run manifests and asynchronous execution control. */
@RestController
@RequestMapping("/api/v1/admin/eval-runs")
public class EvaluationRunAdminController {
    private final EvalRunOrchestrator orchestrator;
    private final EvalRunQueryService queryService;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public EvaluationRunAdminController(EvalRunOrchestrator orchestrator, EvalRunQueryService queryService,
                                        AdminAuthorizationService authorization, AdminAuditLogService audits) {
        this.orchestrator = orchestrator; this.queryService = queryService;
        this.authorization = authorization; this.audits = audits;
    }

    @PostMapping
    public Response<EvalRun> start(@RequestBody StartRequest body, HttpServletRequest request) {
        return execute(request, "START_EVAL_RUN", null, admin -> {
            if (body == null) throw new IllegalArgumentException("Eval Run request is required");
            EvalRunMode mode = body.getMode() == null ? EvalRunMode.MODE_B : EvalRunMode.valueOf(body.getMode().toUpperCase());
            if (mode == EvalRunMode.RELEASE && !authorization.isReleaseOwner(admin)) {
                throw new SecurityException("Release Owner role is required");
            }
            return orchestrator.start(EvalRunStartCommand.builder().mode(mode).idempotencyKey(body.getIdempotencyKey())
                    .datasetId(body.getDatasetId()).datasetVersion(body.getDatasetVersion())
                    .repetitions(body.getRepetitions()).gitSha(body.getGitSha())
                    .baselineRef(body.getBaselineRef()).candidateRef(body.getCandidateRef())
                    .executionProfileHash(body.getExecutionProfileHash()).maxEstimatedCost(body.getMaxEstimatedCost())
                    .minimumCases(body.getMinimumCases()).maximumErrorRate(body.getMaximumErrorRate())
                    .minimumPairedCases(body.getMinimumPairedCases()).regressionThreshold(body.getRegressionThreshold())
                    .createdBy(admin.getId()).build());
        });
    }

    @GetMapping
    public Response<List<EvalRunSummaryView>> list(@RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "0") int offset,
                                        HttpServletRequest request) {
        return execute(request, "LIST_EVAL_RUNS", null, admin -> queryService.list(limit, offset));
    }

    @GetMapping("/{runId}")
    public Response<EvalRunSummaryView> get(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_RUN", runId, admin -> queryService.summary(runId));
    }

    @GetMapping("/{runId}/episodes")
    public Response<List<EvalEpisodeView>> episodes(@PathVariable String runId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String route,
                                                    @RequestParam(required = false) String risk,
                                                    @RequestParam(required = false) String language,
                                                    @RequestParam(required = false) String agent,
                                                    HttpServletRequest request) {
        return execute(request, "LIST_EVAL_EPISODES", runId,
                admin -> queryService.episodes(runId, status, route, risk, language, agent,
                        authorization.evaluationRole(admin)));
    }

    @GetMapping("/{runId}/episodes/{episodeId}")
    public Response<EvalEpisodeDetailView> episode(@PathVariable String runId, @PathVariable String episodeId,
                                                   HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_EPISODE", runId,
                admin -> queryService.detail(runId, episodeId, authorization.evaluationRole(admin)));
    }

    @GetMapping("/{runId}/episodes/{episodeId}/artifact")
    public Response<EvalEpisodeArtifactView> episodeArtifact(@PathVariable String runId,
                                                             @PathVariable String episodeId,
                                                             HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_EPISODE_ARTIFACT", runId,
                admin -> queryService.artifact(runId, episodeId, authorization.evaluationRole(admin)));
    }

    @GetMapping("/{runId}/insights")
    public Response<EvalLiveRunReport> insights(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_RUN_INSIGHTS", runId, admin -> orchestrator.insights(runId));
    }

    @GetMapping("/{runId}/comparison")
    public Response<EvalStatisticalReport.Comparison> comparison(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_RUN_COMPARISON", runId, admin -> orchestrator.comparison(runId));
    }

    @GetMapping("/{runId}/gate")
    public Response<EvalGateDecisionRecord> gate(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_GATE", runId, admin -> orchestrator.gate(runId));
    }

    @PostMapping("/{runId}/gate/evaluate")
    public Response<EvalGateDecisionRecord> evaluateGate(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "EVALUATE_RELEASE_GATE", runId, admin -> {
            requireReleaseOwner(admin); return orchestrator.evaluateGate(runId);
        });
    }

    @PostMapping("/{runId}/gate/override")
    public Response<EvalGateDecisionRecord> overrideGate(@PathVariable String runId, @RequestBody OverrideRequest body,
                                                         HttpServletRequest request) {
        return execute(request, "OVERRIDE_RELEASE_GATE", runId, admin -> {
            requireReleaseOwner(admin);
            return orchestrator.overrideGate(runId, admin.getId(), body == null ? null : body.getReason());
        });
    }

    @PostMapping("/{runId}/cancel")
    public Response<EvalRun> cancel(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "CANCEL_EVAL_RUN", runId, admin -> orchestrator.cancel(runId));
    }

    @PostMapping("/{runId}/retry-errors")
    public Response<EvalRun> retryErrors(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "RETRY_EVAL_RUN_ERRORS", runId, admin -> orchestrator.retryErrors(runId));
    }

    private <T> Response<T> execute(HttpServletRequest request, String action, String target, Operation<T> operation) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return Response.<T>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode()).info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build();
        try {
            T result = operation.run(admin.get()); audit(admin.get(), action, target, "SUCCESS", request);
            return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(result).build();
        } catch (EvalControlPlaneException e) {
            audit(admin.get(), action, target, "REJECTED", request);
            return Response.<T>builder().code(e.getCode().name()).info(e.getMessage()).build();
        } catch (SecurityException e) {
            audit(admin.get(), action, target, "REJECTED", request);
            return Response.<T>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode()).info(e.getMessage()).build();
        } catch (IllegalArgumentException e) {
            audit(admin.get(), action, target, "REJECTED", request);
            return Response.<T>builder().code(EvalControlPlaneErrorCode.VALIDATION_FAILED.name()).info(e.getMessage()).build();
        } catch (IllegalStateException e) {
            audit(admin.get(), action, target, "REJECTED", request);
            return Response.<T>builder().code(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION.name()).info(e.getMessage()).build();
        } catch (RuntimeException e) {
            audit(admin.get(), action, target, "ERROR", request);
            return Response.<T>builder().code(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR.name()).info("Eval Run operation failed").build();
        }
    }

    private void audit(UserAccount admin, String action, String target, String outcome, HttpServletRequest request) {
        audits.record(admin.getId(), action, EvalControlPlaneAuditTypes.EVAL_RUN, target, outcome,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
    private void requireReleaseOwner(UserAccount user) { if (!authorization.isReleaseOwner(user)) throw new SecurityException("Release Owner role is required"); }
    private interface Operation<T> { T run(UserAccount admin); }
    @Data public static class StartRequest {
        private String mode = "MODE_B"; private String idempotencyKey; private String datasetId;
        private String datasetVersion; private int repetitions = 1; private String gitSha;
        private String baselineRef; private String candidateRef; private String executionProfileHash;
        private double maxEstimatedCost; private int minimumCases = 1; private double maximumErrorRate = 0.05D;
        private int minimumPairedCases = 1; private double regressionThreshold;
    }
    @Data public static class OverrideRequest { private String reason; }
}
