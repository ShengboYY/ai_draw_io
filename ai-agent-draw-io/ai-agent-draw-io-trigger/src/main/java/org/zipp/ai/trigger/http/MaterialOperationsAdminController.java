package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.MaterialCapabilityReportDTO;
import org.zipp.ai.api.dto.MaterialProviderCapacityDTO;
import org.zipp.ai.api.dto.RagReleaseDecisionDTO;
import org.zipp.ai.api.dto.RagReleaseReportDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.operations.MaterialCapabilityReport;
import org.zipp.ai.domain.operations.MaterialCapabilityService;
import org.zipp.ai.domain.operations.MaterialFeatureSet;
import org.zipp.ai.domain.operations.MaterialReleaseApproval;
import org.zipp.ai.domain.operations.MaterialProviderCapacityFeed;
import org.zipp.ai.domain.operations.MaterialProviderCapacitySnapshot;
import org.zipp.ai.domain.operations.RagBetaReleaseGate;
import org.zipp.ai.domain.operations.RagCalibrationFallback;
import org.zipp.ai.domain.operations.RagCalibrationSlice;
import org.zipp.ai.domain.operations.RagEvaluationReport;
import org.zipp.ai.domain.operations.RagReleaseMetric;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.trigger.http.service.MaterialCapacityExporterAuthorization;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.TreeMap;

/** Admin-only content-free capability dashboard for material subsystem degradation. */
@RestController
@RequestMapping("/api/v1/admin/material-capabilities")
@ConditionalOnProperty(name = "app.material-operations.enabled", havingValue = "true")
public class MaterialOperationsAdminController {
    private final MaterialCapabilityService capabilities;
    private final MaterialFeatureSet featureSet;
    private final MaterialReleaseApproval releaseApproval;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;
    private final RagBetaReleaseGate releaseGate;
    private final MaterialProviderCapacityFeed providerCapacity;
    private final MaterialCapacityExporterAuthorization capacityExporterAuthorization;

    public MaterialOperationsAdminController(MaterialCapabilityService capabilities,
                                             MaterialFeatureSet featureSet,
                                             MaterialReleaseApproval releaseApproval,
                                             RagBetaReleaseGate releaseGate,
                                             MaterialProviderCapacityFeed providerCapacity,
                                             MaterialCapacityExporterAuthorization capacityExporterAuthorization,
                                             AdminAuthorizationService authorization,
                                             AdminAuditLogService audits) {
        this.capabilities = capabilities;
        this.featureSet = featureSet;
        this.releaseApproval = releaseApproval;
        this.releaseGate = releaseGate;
        this.providerCapacity = providerCapacity;
        this.capacityExporterAuthorization = capacityExporterAuthorization;
        this.authorization = authorization;
        this.audits = audits;
    }

    @PostMapping("/capacity")
    public Response<String> updateCapacity(@RequestBody MaterialProviderCapacityDTO body,
                                           HttpServletRequest request) {
        if (!capacityExporterAuthorization.authorized(request)) {
            return Response.<String>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode())
                    .info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build();
        }
        try {
            if (body == null) throw new IllegalArgumentException("capacity body is required");
            boolean updated = providerCapacity.update(new MaterialProviderCapacitySnapshot(body.capturedAt(),
                    body.sequence(), body.embeddingPercent(), body.vectorReadPercent(),
                    body.vectorWritePercent(), body.dependenciesAvailable()));
            audits.record("material-capacity-exporter", "UPDATE_MATERIAL_PROVIDER_CAPACITY",
                    "MATERIAL_OPERATIONS", null, updated ? "SUCCESS" : "STALE_REJECTED",
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<String>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo()).data(updated ? "accepted" : "stale_ignored").build();
        } catch (IllegalArgumentException exception) {
            audits.record("material-capacity-exporter", "UPDATE_MATERIAL_PROVIDER_CAPACITY",
                    "MATERIAL_OPERATIONS", null, "REJECTED",
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<String>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("invalid material capacity snapshot").build();
        }
    }

    @PostMapping("/release-gate")
    public Response<RagReleaseDecisionDTO> evaluateRelease(@RequestBody RagReleaseReportDTO body,
                                                           HttpServletRequest request) {
        var admin = authorization.currentAdmin(request).filter(authorization::isReleaseOwner);
        if (admin.isEmpty()) {
            return Response.<RagReleaseDecisionDTO>builder().code(ResponseCode.AUTH_FORBIDDEN.getCode())
                    .info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build();
        }
        try {
            RagBetaReleaseGate.Decision decision = releaseGate.evaluate(toDomain(body));
            audits.record(admin.get().getId(), "EVALUATE_MATERIAL_RELEASE_GATE", "MATERIAL_OPERATIONS",
                    body == null ? null : body.datasetVersion(), decision.outcome().name(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<RagReleaseDecisionDTO>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(new RagReleaseDecisionDTO(decision.outcome().name(), decision.violations(),
                            decision.approvalIdentity())).build();
        } catch (IllegalArgumentException exception) {
            // Invalid report bodies are audited but never echo parsing details or supplied metric names.
            audits.record(admin.get().getId(), "EVALUATE_MATERIAL_RELEASE_GATE", "MATERIAL_OPERATIONS",
                    null, "REJECTED", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<RagReleaseDecisionDTO>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("invalid material release report").build();
        }
    }

    @GetMapping
    public Response<MaterialCapabilityReportDTO> report(HttpServletRequest request) {
        return authorization.currentAdmin(request).map(admin -> {
            MaterialCapabilityReport report = capabilities.assess(featureSet, releaseApproval.releasable());
            audits.record(admin.getId(), "VIEW_MATERIAL_CAPABILITIES", "MATERIAL_OPERATIONS",
                    null, "SUCCESS", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<MaterialCapabilityReportDTO>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo()).data(toDto(report)).build();
        }).orElseGet(() -> Response.<MaterialCapabilityReportDTO>builder()
                .code(ResponseCode.AUTH_FORBIDDEN.getCode()).info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build());
    }

    private MaterialCapabilityReportDTO toDto(MaterialCapabilityReport report) {
        TreeMap<String, String> states = new TreeMap<>();
        report.capabilities().forEach((capability, state) -> states.put(capability.name(), state.name()));
        var operations = report.operations();
        return new MaterialCapabilityReportDTO(states, report.overallMaterialState().name(),
                report.capacityLevel().name(), report.capacityUsagePercent(),
                new MaterialCapabilityReportDTO.OperationsDTO(operations.capturedAt(), operations.queuedJobs(),
                        operations.runningJobs(), operations.failedJobsLast24Hours(),
                        operations.oldestQueuedSeconds(), operations.activeReadLeases(),
                        operations.expiredReadLeases(), operations.stuckDeletingMaterials(),
                        operations.oldestDeletionSeconds(), operations.indexedPagesThisMonth(),
                        operations.staleVectorBatches(), operations.openProjectionRepairs(),
                        operations.pendingOrphanDeletions(), operations.purgingGenerations()),
                releaseApproval.releasable(), releaseApproval.reportVersion());
    }

    private RagEvaluationReport toDomain(RagReleaseReportDTO body) {
        if (body == null) throw new IllegalArgumentException("release report is required");
        java.util.EnumMap<RagReleaseMetric, Double> metrics = new java.util.EnumMap<>(RagReleaseMetric.class);
        if (body.metrics() != null) {
            body.metrics().forEach((name, value) -> metrics.put(RagReleaseMetric.valueOf(name), value));
        }
        java.util.List<RagCalibrationSlice> calibrations = body.calibrationSlices() == null
                ? java.util.List.of() : body.calibrationSlices().stream().map(slice -> new RagCalibrationSlice(
                        slice.sliceKey(), RagCalibrationFallback.valueOf(slice.fallback()), slice.sampleCount(),
                        slice.falseSupportedRate(), slice.falseSupportedConfidenceLow(),
                        slice.falseSupportedConfidenceHigh(), slice.falseAbstentionRate(),
                        slice.falseAbstentionConfidenceLow(), slice.falseAbstentionConfidenceHigh())).toList();
        return new RagEvaluationReport(body.schemaVersion(), body.reportId(), body.datasetVersion(),
                body.processingProfile(), body.rankingProfile(), body.modelProfile(), body.deploymentProfile(),
                body.caseCount(), body.lockedCaseCount(), metrics, calibrations,
                body.crossOwnerReadSuccessCount(),
                body.explicitOnlyEscapeCount(), body.deletedOrExpiredLeaseGrantCount(),
                body.preScanDeliveryCount(), body.plainTextRegressionPassed(),
                body.selectionHighlightSmokePassed(), body.atomicCommitSmokePassed(),
                body.answerCanvasInvariantPassed());
    }
}
