package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Admin adapter for immutable Case versions and versioned Dataset membership. */
@RestController
@RequestMapping("/api/v1/admin")
public class EvaluationCatalogAdminController {
    private final EvalCasePublisherService cases;
    private final EvalDatasetService datasets;
    private final EvalDatasetCoverageService coverage;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public EvaluationCatalogAdminController(EvalCasePublisherService cases, EvalDatasetService datasets,
                                            EvalDatasetCoverageService coverage,
                                            AdminAuthorizationService authorization, AdminAuditLogService audits) {
        this.cases = cases; this.datasets = datasets; this.coverage = coverage;
        this.authorization = authorization; this.audits = audits;
    }

    @PostMapping("/eval-case-working-copies/{id}/publish")
    public Response<PublishedCaseDTO> publishCase(@PathVariable String id, HttpServletRequest request) {
        return execute(request, "PUBLISH_EVAL_CASE", "EVAL_CASE_VERSION", id,
                admin -> published(cases.publish(id, admin.getId(), role(admin))));
    }

    @GetMapping("/eval-cases")
    public Response<List<PublishedCaseDTO>> listCases(@RequestParam(required = false) EvaluationTarget target,
                                                      HttpServletRequest request) {
        return execute(request, "LIST_EVAL_CASES", "EVAL_CASE_VERSION", null,
                admin -> cases.list(null).stream()
                        .filter(value -> target == null || value.getEvaluationTarget() == target)
                        .map(this::published).toList());
    }

    @GetMapping("/eval-cases/{caseId}/versions")
    public Response<List<PublishedCaseDTO>> caseVersions(@PathVariable String caseId, HttpServletRequest request) {
        return execute(request, "LIST_EVAL_CASE_VERSIONS", "EVAL_CASE_VERSION", caseId,
                admin -> cases.list(caseId).stream().map(this::published).toList());
    }

    @PostMapping("/eval-cases/{caseId}/clone")
    public Response<EvalCaseWorkingCopy> cloneCase(@PathVariable String caseId, @RequestBody ClonePublishedCaseRequest body,
                                                   HttpServletRequest request) {
        return execute(request, "CLONE_PUBLISHED_EVAL_CASE", "EVAL_CASE_VERSION", caseId,
                admin -> { ClonePublishedCaseRequest value = requireBody(body); return cases.clonePublished(caseId,
                        value.getSourceVersion(), value.getNewCaseId(), value.getNewCaseVersion(), admin.getId(), role(admin)); });
    }

    @PostMapping("/eval-cases/{caseId}/retire")
    public Response<PublishedCaseDTO> retireCase(@PathVariable String caseId, @RequestBody RetireCaseRequest body,
                                                 HttpServletRequest request) {
        return execute(request, "RETIRE_EVAL_CASE", "EVAL_CASE_VERSION", caseId,
                admin -> published(cases.retire(caseId, requireBody(body).getCaseVersion(), admin.getId(), role(admin))));
    }

    @PostMapping("/eval-cases/{caseId}/confirm-target")
    public Response<PublishedCaseDTO> confirmCaseTarget(@PathVariable String caseId,
                                                        @RequestBody ConfirmTargetRequest body,
                                                        HttpServletRequest request) {
        return execute(request, "CONFIRM_EVAL_CASE_TARGET", "EVAL_CASE_VERSION", caseId,
                admin -> { ConfirmTargetRequest value = requireBody(body); return published(cases.confirmTarget(
                        caseId, value.getCaseVersion(), value.getEvaluationTarget(), admin.getId(), role(admin))); });
    }

    @PostMapping("/eval-datasets")
    public Response<EvalDataset> createDataset(@RequestBody CreateDatasetRequest body, HttpServletRequest request) {
        return execute(request, "CREATE_EVAL_DATASET", "EVAL_DATASET", null, admin -> {
            CreateDatasetRequest value = requireBody(body);
            if (value.getDatasetClass() == null) throw new IllegalArgumentException("datasetClass is required");
            EvalDatasetClass datasetClass = EvalDatasetClass.valueOf(value.getDatasetClass().trim().toUpperCase());
            if (datasetClass == EvalDatasetClass.SEQUESTERED) throw new SecurityException("Release Owner role is required");
            return datasets.create(value.getName(), datasetClass, admin.getId(), role(admin));
        });
    }

    @GetMapping("/eval-datasets")
    public Response<List<EvalDataset>> listDatasets(@RequestParam(required = false) EvaluationTarget target,
                                                    HttpServletRequest request) {
        return execute(request, "LIST_EVAL_DATASETS", "EVAL_DATASET", null,
                admin -> datasets.list().stream()
                        .filter(value -> value.getDatasetClass() != EvalDatasetClass.SEQUESTERED)
                        .filter(value -> target == null || value.getEvaluationTarget() == target).toList());
    }

    @GetMapping("/evaluation-targets")
    public Response<List<EvaluationTarget>> targets(HttpServletRequest request) {
        return execute(request, "LIST_EVALUATION_TARGETS", "EVALUATION_TARGET", null,
                admin -> List.of(EvaluationTarget.values()));
    }

    @GetMapping("/eval-datasets/{id}/versions")
    public Response<List<EvalDatasetVersion>> datasetVersions(@PathVariable String id, HttpServletRequest request) {
        return execute(request, "LIST_EVAL_DATASET_VERSIONS", "EVAL_DATASET", id,
                admin -> datasets.versions(id).stream().filter(value -> value.getDatasetClass() != EvalDatasetClass.SEQUESTERED).toList());
    }

    @PostMapping("/eval-datasets/{id}/versions")
    public Response<EvalDatasetVersion> createDatasetVersion(@PathVariable String id,
                                                             @RequestBody DatasetVersionRequest body,
                                                             HttpServletRequest request) {
        return execute(request, "CREATE_EVAL_DATASET_VERSION", "EVAL_DATASET", id,
                admin -> { DatasetVersionRequest value = requireBody(body); return datasets.createVersion(id,
                        value.getVersion(), value.getMembers(), admin.getId(), role(admin)); });
    }

    @PostMapping("/eval-datasets/{id}/clone")
    public Response<EvalDatasetVersion> cloneDataset(@PathVariable String id, @RequestBody CloneDatasetRequest body,
                                                     HttpServletRequest request) {
        return execute(request, "CLONE_EVAL_DATASET_VERSION", "EVAL_DATASET", id,
                admin -> { CloneDatasetRequest value = requireBody(body); return datasets.cloneVersion(id,
                        value.getSourceVersion(), value.getTargetDatasetId(), value.getTargetVersion(), admin.getId(), role(admin)); });
    }

    @PutMapping("/eval-datasets/{id}/members")
    public Response<EvalDatasetVersion> replaceMembers(@PathVariable String id, @RequestBody DatasetMembersRequest body,
                                                       HttpServletRequest request) {
        return execute(request, "UPDATE_EVAL_DATASET_MEMBERS", "EVAL_DATASET", id,
                admin -> { DatasetMembersRequest value = requireBody(body); return datasets.replaceMembers(id,
                        value.getVersion(), value.getExpectedRevision(), value.getMembers(), admin.getId(), role(admin)); });
    }

    @PostMapping("/eval-datasets/{id}/validate")
    public Response<EvalDatasetVersion> validateDataset(@PathVariable String id, @RequestBody DatasetActionRequest body,
                                                        HttpServletRequest request) {
        return execute(request, "VALIDATE_EVAL_DATASET", "EVAL_DATASET", id,
                admin -> datasets.validate(id, requireBody(body).getVersion(), admin.getId(), role(admin)));
    }

    @PostMapping("/eval-datasets/{id}/publish")
    public Response<EvalDatasetVersion> publishDataset(@PathVariable String id, @RequestBody DatasetActionRequest body,
                                                       HttpServletRequest request) {
        return execute(request, "PUBLISH_EVAL_DATASET", "EVAL_DATASET", id,
                admin -> datasets.publish(id, requireBody(body).getVersion(), admin.getId(), role(admin)));
    }

    @GetMapping("/eval-datasets/{id}/coverage")
    public Response<EvalDatasetCoverage> datasetCoverage(@PathVariable String id, @RequestParam String version,
                                                         HttpServletRequest request) {
        return execute(request, "VIEW_EVAL_DATASET_COVERAGE", "EVAL_DATASET", id,
                admin -> coverage.coverage(id, version, role(admin)));
    }

    private PublishedCaseDTO published(EvalCaseVersion value) {
        return new PublishedCaseDTO(value.getCaseId(), value.getCaseVersion(), value.getContentHash(),
                value.getEvaluationTarget(), value.getTargetMigrationStatus() == null ? null : value.getTargetMigrationStatus().name(),
                value.getApprovedBy(), value.getPublishedAt(), value.getRetiredAt());
    }

    private <T> T requireBody(T value) {
        if (value == null) throw new IllegalArgumentException("request body is required");
        return value;
    }

    private EvalAdminRole role(UserAccount admin) {
        return authorization.evaluationRole(admin);
    }

    private <T> Response<T> execute(HttpServletRequest request, String action, String resourceType, String target,
                                    Operation<T> operation) {
        Optional<UserAccount> admin = authorization.currentAdmin(request);
        if (admin.isEmpty()) return Response.<T>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode()).info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build();
        try {
            T result = operation.run(admin.get());
            audits.record(admin.get().getId(), action, resourceType, target, "SUCCESS", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(result).build();
        } catch (EvalControlPlaneException e) {
            audits.record(admin.get().getId(), action, resourceType, target, "REJECTED", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<T>builder().code(e.getCode().name()).info(e.getMessage()).build();
        } catch (SecurityException e) {
            audits.record(admin.get().getId(), action, resourceType, target, "REJECTED", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<T>builder().code(EvalControlPlaneErrorCode.FORBIDDEN.name()).info(e.getMessage()).build();
        } catch (IllegalArgumentException e) {
            audits.record(admin.get().getId(), action, resourceType, target, "REJECTED", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<T>builder().code(EvalControlPlaneErrorCode.VALIDATION_FAILED.name()).info(e.getMessage()).build();
        } catch (IllegalStateException e) {
            audits.record(admin.get().getId(), action, resourceType, target, "REJECTED", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<T>builder().code(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION.name()).info(e.getMessage()).build();
        } catch (RuntimeException e) {
            audits.record(admin.get().getId(), action, resourceType, target, "ERROR", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<T>builder().code(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR.name()).info("evaluation catalog operation failed").build();
        }
    }

    private interface Operation<T> { T run(UserAccount admin); }
    public record PublishedCaseDTO(String caseId, String caseVersion, String contentHash,
                                   EvaluationTarget evaluationTarget, String targetMigrationStatus, String approvedBy,
                                   Instant publishedAt, Instant retiredAt) { }
    @Data public static class ClonePublishedCaseRequest { private String sourceVersion; private String newCaseId; private String newCaseVersion; }
    @Data public static class RetireCaseRequest { private String caseVersion; }
    @Data public static class ConfirmTargetRequest { private String caseVersion; private EvaluationTarget evaluationTarget; }
    @Data public static class CreateDatasetRequest { private String name; private String datasetClass; }
    @Data public static class DatasetVersionRequest { private String version; private List<EvalDatasetMember> members; }
    @Data public static class CloneDatasetRequest { private String sourceVersion; private String targetDatasetId; private String targetVersion; }
    @Data public static class DatasetMembersRequest { private String version; private long expectedRevision; private List<EvalDatasetMember> members; }
    @Data public static class DatasetActionRequest { private String version; }
}
