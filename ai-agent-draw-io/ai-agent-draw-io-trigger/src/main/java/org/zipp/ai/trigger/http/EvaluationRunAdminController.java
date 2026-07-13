package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneAuditTypes;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneErrorCode;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalRunOrchestrator;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.List;
import java.util.Optional;

/** Admin API for immutable Mode B run manifests and asynchronous execution control. */
@RestController
@RequestMapping("/api/v1/admin/eval-runs")
public class EvaluationRunAdminController {
    private final EvalRunOrchestrator orchestrator;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public EvaluationRunAdminController(EvalRunOrchestrator orchestrator,
                                        AdminAuthorizationService authorization, AdminAuditLogService audits) {
        this.orchestrator = orchestrator; this.authorization = authorization; this.audits = audits;
    }

    @PostMapping
    public Response<EvalRun> start(@RequestBody StartRequest body, HttpServletRequest request) {
        return execute(request, "START_EVAL_RUN", null, admin -> {
            if (body == null || (body.getMode() != null && !"MODE_B".equalsIgnoreCase(body.getMode()))) {
                throw new IllegalArgumentException("CP4 supports MODE_B only");
            }
            return orchestrator.start(EvalRunStartCommand.builder().idempotencyKey(body.getIdempotencyKey())
                    .datasetId(body.getDatasetId()).datasetVersion(body.getDatasetVersion())
                    .repetitions(body.getRepetitions()).gitSha(body.getGitSha())
                    .executionProfileHash(body.getExecutionProfileHash()).createdBy(admin.getId()).build());
        });
    }

    @GetMapping
    public Response<List<EvalRun>> list(@RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "0") int offset,
                                        HttpServletRequest request) {
        return execute(request, "LIST_EVAL_RUNS", null, admin -> orchestrator.list(limit, offset));
    }

    @GetMapping("/{runId}")
    public Response<EvalRun> get(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_RUN", runId, admin -> orchestrator.get(runId));
    }

    @GetMapping("/{runId}/episodes")
    public Response<List<EvalEpisode>> episodes(@PathVariable String runId, HttpServletRequest request) {
        return execute(request, "LIST_EVAL_EPISODES", runId, admin -> orchestrator.episodes(runId));
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(admin.get(), action, target, "REJECTED", request);
            EvalControlPlaneErrorCode code = e.getMessage() != null && e.getMessage().contains("not found")
                    ? EvalControlPlaneErrorCode.NOT_FOUND : EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION;
            return Response.<T>builder().code(code.name()).info(e.getMessage()).build();
        } catch (RuntimeException e) {
            audit(admin.get(), action, target, "ERROR", request);
            return Response.<T>builder().code(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR.name()).info("Eval Run operation failed").build();
        }
    }

    private void audit(UserAccount admin, String action, String target, String outcome, HttpServletRequest request) {
        audits.record(admin.getId(), action, EvalControlPlaneAuditTypes.EVAL_RUN, target, outcome,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
    private interface Operation<T> { T run(UserAccount admin); }
    @Data public static class StartRequest {
        private String mode = "MODE_B"; private String idempotencyKey; private String datasetId;
        private String datasetVersion; private int repetitions = 1; private String gitSha;
        private String executionProfileHash;
    }
}
