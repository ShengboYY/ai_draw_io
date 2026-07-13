package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCandidatePromotionLink;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCasePromotionResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingView;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceFindingViewService;

import java.time.Clock;
import java.time.Duration;

/** Owns the one-way Finding -> Draft -> immutable Case bridge. */
@Service
public class EvalCasePromotionService {
    private static final Duration LINK_RETENTION = Duration.ofDays(90);

    private final EvalCaseWorkingCopyService workingCopies;
    private final EvalCasePublisherService publisher;
    private final EvalCaseValidationService validation;
    private final ITraceToEvalStore candidates;
    private final IEvalCandidatePromotionLinkStore links;
    private final TraceFindingViewService findings;
    private final Clock clock;

    @Autowired
    public EvalCasePromotionService(EvalCaseWorkingCopyService workingCopies, EvalCasePublisherService publisher,
                                    EvalCaseValidationService validation, ITraceToEvalStore candidates,
                                    IEvalCandidatePromotionLinkStore links, TraceFindingViewService findings) {
        this(workingCopies, publisher, validation, candidates, links, findings, Clock.systemUTC());
    }

    public EvalCasePromotionService(EvalCaseWorkingCopyService workingCopies, EvalCasePublisherService publisher,
                                    EvalCaseValidationService validation, ITraceToEvalStore candidates,
                                    IEvalCandidatePromotionLinkStore links, TraceFindingViewService findings,
                                    Clock clock) {
        this.workingCopies = workingCopies;
        this.publisher = publisher;
        this.validation = validation;
        this.candidates = candidates;
        this.links = links;
        this.findings = findings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCasePromotionResult promote(String candidateId, String caseId, String caseVersion,
                                           String actor, EvalAdminRole role) {
        EvalCandidatePromotionLink published = links.findByCandidateId(required(candidateId, "candidateId")).orElse(null);
        if (published != null) return result(EvalCasePromotionResult.Status.ALREADY_PUBLISHED, published);

        EvalCaseCandidate candidate = candidates.findCandidate(candidateId)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "Finding not found"));
        rejectTerminal(candidate);

        EvalCaseWorkingCopy existing = workingCopies.findByCandidateId(candidateId, actor, role).orElse(null);
        if (existing != null) return result(EvalCasePromotionResult.Status.EXISTING_DRAFT, candidateId, existing);
        if (candidate.getStatus() != EvalCandidateStatus.DRAFT_READY) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION,
                    "Finding must be DRAFT_READY before Promote");
        }
        EvalCaseWorkingCopy created = workingCopies.createFromTraceDraft(candidateId, caseId, caseVersion, actor, role);
        EvalCasePromotionResult.Status status = created.getCaseId().equals(caseId)
                && created.getCaseVersion().equals(caseVersion)
                ? EvalCasePromotionResult.Status.CREATED : EvalCasePromotionResult.Status.EXISTING_DRAFT;
        return result(status, candidateId, created);
    }

    /** DB writes roll back together; a content-addressed artifact may remain as an unreferenced safe blob. */
    @Transactional
    public EvalCaseVersion publish(String workingCopyId, String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy workingCopy = workingCopies.get(workingCopyId, actor, role);
        String candidateId = workingCopy.getCandidateId();
        if (candidateId == null) return publisher.publish(workingCopyId, actor, role);

        EvalCaseCandidate candidate = candidates.findCandidate(candidateId)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "Finding not found"));
        rejectTerminal(candidate);
        validation.assertPrivacySafeForPublication(workingCopy.getDefinition());
        EvalCandidatePromotionLink proposed = EvalCandidatePromotionLink.builder()
                .candidateId(candidateId).workingCopyId(workingCopyId).caseId(workingCopy.getCaseId())
                .caseVersion(workingCopy.getCaseVersion()).promotedAt(clock.instant())
                .retentionExpiresAt(clock.instant().plus(LINK_RETENTION)).build();
        EvalCandidatePromotionLink canonical = links.insertIfAbsent(proposed);
        requireSamePublication(proposed, canonical);

        EvalCaseVersion version = publisher.publish(workingCopyId, actor, role);
        candidates.updateCandidateStatus(candidateId, EvalCandidateStatus.PUBLISHED);
        return version;
    }

    public TraceFindingView sourceFinding(String workingCopyId, String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy workingCopy = workingCopies.get(workingCopyId, actor, role);
        String candidateId = workingCopy.getCandidateId();
        if (candidateId == null) {
            EvalCandidatePromotionLink link = links.findByWorkingCopyId(workingCopyId)
                    .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                            "source Finding not found"));
            if (link.getRetentionExpiresAt() != null && link.getRetentionExpiresAt().isBefore(clock.instant())) {
                throw new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "source Finding retention has expired");
            }
            candidateId = link.getCandidateId();
        }
        return findings.find(candidateId);
    }

    private void rejectTerminal(EvalCaseCandidate candidate) {
        if (candidate.getStatus() == EvalCandidateStatus.REJECTED
                || candidate.getStatus() == EvalCandidateStatus.EXPIRED
                || candidate.getStatus() == EvalCandidateStatus.PURGED) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION,
                    "Finding cannot be promoted in status=" + candidate.getStatus());
        }
        if (candidate.getStatus() == EvalCandidateStatus.PUBLISHED) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.INFRASTRUCTURE_ERROR,
                    "Published Finding is missing its restricted promotion link");
        }
    }

    private void requireSamePublication(EvalCandidatePromotionLink proposed, EvalCandidatePromotionLink canonical) {
        if (!proposed.getCandidateId().equals(canonical.getCandidateId())
                || !proposed.getWorkingCopyId().equals(canonical.getWorkingCopyId())
                || !proposed.getCaseId().equals(canonical.getCaseId())
                || !proposed.getCaseVersion().equals(canonical.getCaseVersion())) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION,
                    "Finding was already linked to a different publication");
        }
    }

    private EvalCasePromotionResult result(EvalCasePromotionResult.Status status, EvalCandidatePromotionLink link) {
        return new EvalCasePromotionResult(status, link.getCandidateId(), link.getWorkingCopyId(),
                link.getCaseId(), link.getCaseVersion());
    }

    private EvalCasePromotionResult result(EvalCasePromotionResult.Status status, String candidateId,
                                           EvalCaseWorkingCopy workingCopy) {
        return new EvalCasePromotionResult(status, candidateId, workingCopy.getId(),
                workingCopy.getCaseId(), workingCopy.getCaseVersion());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
