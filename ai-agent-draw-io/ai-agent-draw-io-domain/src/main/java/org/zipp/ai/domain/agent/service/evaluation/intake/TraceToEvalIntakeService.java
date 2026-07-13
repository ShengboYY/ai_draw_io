package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

/** P0 manual intake. It reads metadata-only telemetry and never accesses debug trace content or an LLM. */
@Service
public class TraceToEvalIntakeService {
    private static final String MANUAL_FAILURE_FAMILY = "manual_review";
    private final IAgentUsageTelemetryStore telemetryStore;
    private final ITraceToEvalStore intakeStore;
    private final Clock clock;

    @Autowired
    public TraceToEvalIntakeService(IAgentUsageTelemetryStore telemetryStore, ITraceToEvalStore intakeStore) {
        this(telemetryStore, intakeStore, Clock.systemUTC());
    }

    TraceToEvalIntakeService(IAgentUsageTelemetryStore telemetryStore, ITraceToEvalStore intakeStore, Clock clock) {
        this.telemetryStore = telemetryStore;
        this.intakeStore = intakeStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCaseCandidate createManualCandidate(String runId, String actor) {
        require(runId, "runId"); require(actor, "actor");
        Optional<EvalCaseCandidate> existing = intakeStore.findCandidateBySourceRunAndFailureFamily(runId, MANUAL_FAILURE_FAMILY);
        if (existing.isPresent()) return existing.get();
        AgentRunDetail detail = telemetryStore.findRunDetail(runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        AgentRunTelemetry run = detail.getRun();
        if (run == null) throw new IllegalArgumentException("run metadata is unavailable");
        AgentRunStepTelemetry failed = detail.getSteps().stream().filter(step -> "FAILED".equals(step.getStatus()))
                .max(Comparator.comparing(AgentRunStepTelemetry::getCompletedAt, Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
        EvalCaseCandidate candidate = EvalCaseCandidate.builder().id("ecc_" + UUID.randomUUID())
                .sourceRunId(run.getId()).sourceSpanId(failed == null ? null : failed.getId())
                .sourcePhase(failed == null ? null : failed.getPhase()).sourceAgentId(run.getAgentId())
                .failureFamily(MANUAL_FAILURE_FAMILY).ruleId("manual_run_selection")
                .evidenceSummary(failed == null ? "Manual review of run status=" + StringUtils.defaultIfBlank(run.getStatus(), "UNKNOWN")
                        : "Manual review of failed phase=" + failed.getPhase())
                .risk("high").discoveredAt(clock.instant()).policyVersion("trace-intake-p0")
                .status(EvalCandidateStatus.DETECTED).createdBy(actor).detectionSource("MANUAL").build();
        intakeStore.insertCandidate(candidate);
        return candidate;
    }

    public List<EvalCaseCandidate> listCandidates(String status, String risk, int requestedLimit, int requestedOffset) {
        EvalCandidateStatus parsedStatus = StringUtils.isBlank(status) ? null : parseStatus(status);
        String parsedRisk = StringUtils.trimToNull(StringUtils.lowerCase(risk));
        if (parsedRisk != null && !Set.of("critical", "high", "medium", "low").contains(parsedRisk)) {
            throw new IllegalArgumentException("unsupported risk");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        int offset = Math.max(0, requestedOffset);
        return intakeStore.listCandidates(parsedStatus, parsedRisk, limit, offset);
    }

    @Transactional
    public EvalCaseCandidate transition(String candidateId, String requestedStatus, String actor, String reason) {
        require(candidateId, "candidateId"); require(actor, "actor");
        EvalCandidateStatus target = parseStatus(requestedStatus);
        if (target == EvalCandidateStatus.PUBLISHED || target == EvalCandidateStatus.APPROVED) {
            throw new IllegalArgumentException("approval and publication require their dedicated review flow");
        }
        EvalCaseCandidate candidate = intakeStore.findCandidate(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("candidate not found"));
        if (!allowedTransition(candidate.getStatus(), target)) {
            throw new IllegalStateException("invalid candidate transition: " + candidate.getStatus() + " -> " + target);
        }
        intakeStore.insertReview(EvalCaseReview.builder().id("ecr_" + UUID.randomUUID()).candidateId(candidateId)
                .reviewer(actor).decision(target.name()).reason(StringUtils.left(StringUtils.trimToEmpty(reason), 1024))
                .reviewedAt(clock.instant()).build());
        intakeStore.updateCandidateStatus(candidateId, target);
        candidate.setStatus(target);
        return candidate;
    }

    @Transactional
    public EvalCaseCandidate review(String candidateId, String decision, String actor, String reason) {
        require(candidateId, "candidateId"); require(actor, "actor");
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(decision));
        EvalCandidateStatus next = "APPROVE".equals(normalized) ? EvalCandidateStatus.APPROVED
                : "REJECT".equals(normalized) ? EvalCandidateStatus.REJECTED : null;
        if (next == null) throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        EvalCaseCandidate candidate = intakeStore.findCandidate(candidateId).orElseThrow(() -> new IllegalArgumentException("candidate not found"));
        if (candidate.getStatus() != EvalCandidateStatus.DETECTED) throw new IllegalStateException("candidate is not awaiting review");
        intakeStore.insertReview(EvalCaseReview.builder().id("ecr_" + UUID.randomUUID()).candidateId(candidateId)
                .reviewer(actor).decision(normalized).reason(StringUtils.left(StringUtils.trimToEmpty(reason), 1024)).reviewedAt(clock.instant()).build());
        intakeStore.updateCandidateStatus(candidateId, next); candidate.setStatus(next); return candidate;
    }

    @Transactional
    public EvalCaseLineage recordPublication(String candidateId, String caseId, String datasetVersion, String sanitizerVersion, String actor) {
        require(candidateId, "candidateId"); require(caseId, "caseId"); require(datasetVersion, "datasetVersion"); require(actor, "actor");
        EvalCaseCandidate candidate = intakeStore.findCandidate(candidateId).orElseThrow(() -> new IllegalArgumentException("candidate not found"));
        if (candidate.getStatus() != EvalCandidateStatus.APPROVED) throw new IllegalStateException("candidate must be approved before publication");
        EvalCaseLineage lineage = EvalCaseLineage.builder().promotionId("ecp_" + UUID.randomUUID()).caseId(caseId)
                .datasetVersion(datasetVersion).reviewer(actor).approvedAt(clock.instant())
                .sanitizerVersion(StringUtils.defaultIfBlank(sanitizerVersion, "manual-synthesis")).origin("trace-derived-synthetic").build();
        intakeStore.insertLineage(lineage); intakeStore.updateCandidateStatus(candidateId, EvalCandidateStatus.PUBLISHED); return lineage;
    }

    private void require(String value, String field) { if (StringUtils.isBlank(value)) throw new IllegalArgumentException(field + " is required"); }

    private EvalCandidateStatus parseStatus(String status) {
        try {
            return EvalCandidateStatus.valueOf(StringUtils.upperCase(StringUtils.trimToEmpty(status)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported candidate status");
        }
    }

    private boolean allowedTransition(EvalCandidateStatus current, EvalCandidateStatus target) {
        if (current == null || current == target) return false;
        if (Set.of(EvalCandidateStatus.REJECTED, EvalCandidateStatus.EXPIRED, EvalCandidateStatus.PURGED).contains(target)) {
            return !Set.of(EvalCandidateStatus.PUBLISHED, EvalCandidateStatus.REJECTED,
                    EvalCandidateStatus.EXPIRED, EvalCandidateStatus.PURGED).contains(current);
        }
        return switch (current) {
            case DETECTED -> target == EvalCandidateStatus.TRIAGED;
            case TRIAGED -> target == EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION
                    || target == EvalCandidateStatus.UNDER_REVIEW;
            case DRAFT_READY, NEEDS_MANUAL_RECONSTRUCTION -> target == EvalCandidateStatus.UNDER_REVIEW;
            default -> false;
        };
    }
}
