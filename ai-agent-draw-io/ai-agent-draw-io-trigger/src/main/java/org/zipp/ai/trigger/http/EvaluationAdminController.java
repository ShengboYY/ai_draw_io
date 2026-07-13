package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseSourceType;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseValidationResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseDryRunResult;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseDryRunService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseReviewService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseValidationService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseWorkingCopyService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneAuditTypes;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneErrorCode;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.List;
import java.util.Optional;

/** Admin-only HTTP adapter for mutable Eval Case working copies. */
@RestController
@RequestMapping("/api/v1/admin/eval-case-working-copies")
public class EvaluationAdminController {
    private final EvalCaseWorkingCopyService service;
    private final EvalCaseValidationService validationService;
    private final EvalCaseDryRunService dryRunService;
    private final EvalCaseReviewService reviewService;
    private final AdminAuthorizationService authorizationService;
    private final AdminAuditLogService auditLogService;

    public EvaluationAdminController(EvalCaseWorkingCopyService service,
                                     EvalCaseValidationService validationService,
                                     EvalCaseDryRunService dryRunService,
                                     EvalCaseReviewService reviewService,
                                     AdminAuthorizationService authorizationService,
                                     AdminAuditLogService auditLogService) {
        this.service = service;
        this.validationService = validationService;
        this.dryRunService = dryRunService;
        this.reviewService = reviewService;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/{workingCopyId}/validate")
    public Response<EvalCaseValidationResult> validate(@PathVariable String workingCopyId,
                                                       HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseValidationResult result = validationService.validate(workingCopyId,
                    admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "VALIDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "SUCCESS", request);
            return success(result);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            audit(admin.get(), "VALIDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "VALIDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to validate working copy");
        }
    }

    @PostMapping("/{workingCopyId}/dry-runs")
    public Response<EvalCaseDryRunResult> dryRun(@PathVariable String workingCopyId,
                                                 HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseDryRunResult result = dryRunService.run(workingCopyId,
                    admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "DRY_RUN_EVAL_CASE_WORKING_COPY", workingCopyId, "SUCCESS", request);
            return success(result);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            audit(admin.get(), "DRY_RUN_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "DRY_RUN_EVAL_CASE_WORKING_COPY", workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to dry-run working copy");
        }
    }

    @PostMapping("/{workingCopyId}/submit-review")
    public Response<EvalCaseWorkingCopy> submitReview(@PathVariable String workingCopyId,
                                                      HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseWorkingCopy result = reviewService.submit(workingCopyId,
                    admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "SUBMIT_EVAL_CASE_REVIEW", workingCopyId, "SUCCESS", request);
            return success(result);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            audit(admin.get(), "SUBMIT_EVAL_CASE_REVIEW", workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "SUBMIT_EVAL_CASE_REVIEW", workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to submit working copy review");
        }
    }

    @PostMapping("/{workingCopyId}/approve")
    public Response<EvalCaseWorkingCopy> approve(@PathVariable String workingCopyId,
                                                 @RequestBody ReviewDecisionRequest body,
                                                 HttpServletRequest request) {
        return review(workingCopyId, "APPROVE", body, request);
    }

    @PostMapping("/{workingCopyId}/reject")
    public Response<EvalCaseWorkingCopy> reject(@PathVariable String workingCopyId,
                                                @RequestBody ReviewDecisionRequest body,
                                                HttpServletRequest request) {
        return review(workingCopyId, "REJECT", body, request);
    }

    private Response<EvalCaseWorkingCopy> review(String workingCopyId, String decision,
                                                 ReviewDecisionRequest body, HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        String action = decision + "_EVAL_CASE_WORKING_COPY";
        try {
            EvalCaseWorkingCopy result = reviewService.decide(workingCopyId, decision,
                    body == null ? null : body.getReason(), admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), action, workingCopyId, "SUCCESS", request);
            return success(result);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            audit(admin.get(), action, workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), action, workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to review working copy");
        }
    }

    @PostMapping
    public Response<EvalCaseWorkingCopy> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseSourceType sourceType = parseSourceType(body == null ? null : body.getSourceType());
            EvalCaseWorkingCopy created = switch (sourceType) {
                case MANUAL -> service.createManual(body.getDefinition(), admin.get().getId(), EvalAdminRole.ADMIN);
                case IMPORTED -> service.createImportedYaml(body.getYaml(), admin.get().getId(), EvalAdminRole.ADMIN);
                case TRACE_DRAFT -> service.createFromTraceDraft(body.getCandidateId(), body.getCaseId(),
                        body.getCaseVersion(), admin.get().getId(), EvalAdminRole.ADMIN);
                default -> throw new IllegalArgumentException("sourceType is not valid for create");
            };
            audit(admin.get(), "CREATE_EVAL_CASE_WORKING_COPY", created.getId(), "SUCCESS", request);
            return success(created);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "CREATE_EVAL_CASE_WORKING_COPY", null, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "CREATE_EVAL_CASE_WORKING_COPY", null, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to create working copy");
        }
    }

    @GetMapping
    public Response<List<EvalCaseWorkingCopy>> list(@RequestParam(value = "status", required = false) String status,
                                                    @RequestParam(value = "owner", required = false) String owner,
                                                    @RequestParam(value = "limit", defaultValue = "50") int limit,
                                                    @RequestParam(value = "offset", defaultValue = "0") int offset,
                                                    HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            List<EvalCaseWorkingCopy> result = service.list(status, owner, limit, offset,
                    admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "LIST_EVAL_CASE_WORKING_COPIES", null, "SUCCESS", request);
            return success(result);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "LIST_EVAL_CASE_WORKING_COPIES", null, "REJECTED", request);
            return failure(EvalControlPlaneErrorCode.VALIDATION_FAILED, e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "LIST_EVAL_CASE_WORKING_COPIES", null, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to list working copies");
        }
    }

    @GetMapping("/{workingCopyId}")
    public Response<EvalCaseWorkingCopy> get(@PathVariable String workingCopyId, HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseWorkingCopy result = service.get(workingCopyId, admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "VIEW_EVAL_CASE_WORKING_COPY", workingCopyId, "SUCCESS", request);
            return success(result);
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "VIEW_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "VIEW_EVAL_CASE_WORKING_COPY", workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to load working copy");
        }
    }

    @PutMapping("/{workingCopyId}")
    public Response<EvalCaseWorkingCopy> update(@PathVariable String workingCopyId,
                                                @RequestBody UpdateRequest body,
                                                HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            if (body == null || body.getExpectedRevision() == null) {
                throw new IllegalArgumentException("expectedRevision is required");
            }
            EvalCaseWorkingCopy result = service.update(workingCopyId, body.getExpectedRevision(),
                    body.getDefinition(), admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "UPDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "SUCCESS", request);
            return success(result);
        } catch (SecurityException e) {
            audit(admin.get(), "UPDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            return failure(EvalControlPlaneErrorCode.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            audit(admin.get(), "UPDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (IllegalStateException e) {
            audit(admin.get(), "UPDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            EvalControlPlaneErrorCode code = StringUtils.containsIgnoreCase(e.getMessage(), "revision")
                    ? EvalControlPlaneErrorCode.REVISION_CONFLICT
                    : EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION;
            return failure(code, e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "UPDATE_EVAL_CASE_WORKING_COPY", workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to update working copy");
        }
    }

    @PostMapping("/{workingCopyId}/clone")
    public Response<EvalCaseWorkingCopy> cloneWorkingCopy(@PathVariable String workingCopyId,
                                                          @RequestBody CloneRequest body,
                                                          HttpServletRequest request) {
        Optional<UserAccount> admin = authorizationService.currentAdmin(request);
        if (admin.isEmpty()) return forbidden();
        try {
            EvalCaseWorkingCopy clone = service.cloneWorkingCopy(workingCopyId,
                    body == null ? null : body.getCaseId(), body == null ? null : body.getCaseVersion(),
                    admin.get().getId(), EvalAdminRole.ADMIN);
            audit(admin.get(), "CLONE_EVAL_CASE_WORKING_COPY", clone.getId(), "SUCCESS", request);
            return success(clone);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            audit(admin.get(), "CLONE_EVAL_CASE_WORKING_COPY", workingCopyId, "REJECTED", request);
            return failure(codeFor(e), e.getMessage());
        } catch (RuntimeException e) {
            audit(admin.get(), "CLONE_EVAL_CASE_WORKING_COPY", workingCopyId, "ERROR", request);
            return failure(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR, "failed to clone working copy");
        }
    }

    private EvalCaseSourceType parseSourceType(String value) {
        if (StringUtils.isBlank(value)) return EvalCaseSourceType.MANUAL;
        try {
            return EvalCaseSourceType.valueOf(StringUtils.upperCase(StringUtils.trim(value)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported sourceType");
        }
    }

    private EvalControlPlaneErrorCode codeFor(RuntimeException error) {
        if (error instanceof SecurityException) return EvalControlPlaneErrorCode.FORBIDDEN;
        if (StringUtils.containsIgnoreCase(error.getMessage(), "not found")) return EvalControlPlaneErrorCode.NOT_FOUND;
        if (StringUtils.containsIgnoreCase(error.getMessage(), "revision")) return EvalControlPlaneErrorCode.REVISION_CONFLICT;
        if (error instanceof IllegalStateException) return EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION;
        return EvalControlPlaneErrorCode.VALIDATION_FAILED;
    }

    private void audit(UserAccount admin, String action, String targetId, String outcome, HttpServletRequest request) {
        auditLogService.record(admin.getId(), action, EvalControlPlaneAuditTypes.CASE_WORKING_COPY,
                targetId, outcome, clientIp(request), userAgent(request));
    }

    private String clientIp(HttpServletRequest request) {
        return request == null ? null : StringUtils.trimToNull(request.getRemoteAddr());
    }

    private String userAgent(HttpServletRequest request) {
        String value = request == null ? null : StringUtils.trimToNull(request.getHeader("User-Agent"));
        return value == null ? null : StringUtils.left(value, 256);
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(EvalControlPlaneErrorCode code, String message) {
        return Response.<T>builder().code(code.name()).info(message).build();
    }

    private <T> Response<T> forbidden() {
        return Response.<T>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode())
                .info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build();
    }

    @Data
    public static class CreateRequest {
        private String sourceType;
        private String candidateId;
        private String caseId;
        private String caseVersion;
        private String yaml;
        private EvalCaseDefinition definition;
    }

    @Data
    public static class UpdateRequest {
        private Long expectedRevision;
        private EvalCaseDefinition definition;
    }

    @Data
    public static class CloneRequest {
        private String caseId;
        private String caseVersion;
    }

    @Data
    public static class ReviewDecisionRequest {
        private String reason;
    }
}
