package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;

import java.time.Clock;
import java.util.Comparator;

/** Publishes approved synthetic cases into immutable content-addressed artifacts. */
@Service
public class EvalCasePublisherService {
    private final EvalCaseWorkingCopyService workingCopies;
    private final IEvalCaseVersionStore versions;
    private final IEvalCaseArtifactStore artifacts;
    private final IEvalCaseWorkingCopyReviewStore reviews;
    private final Clock clock;
    private final EvalContentSupport content = new EvalContentSupport();

    @Autowired
    public EvalCasePublisherService(EvalCaseWorkingCopyService workingCopies, IEvalCaseVersionStore versions,
                                    IEvalCaseArtifactStore artifacts, IEvalCaseWorkingCopyReviewStore reviews) {
        this(workingCopies, versions, artifacts, reviews, Clock.systemUTC());
    }

    public EvalCasePublisherService(EvalCaseWorkingCopyService workingCopies, IEvalCaseVersionStore versions,
                                    IEvalCaseArtifactStore artifacts, IEvalCaseWorkingCopyReviewStore reviews,
                                    Clock clock) {
        this.workingCopies = workingCopies; this.versions = versions; this.artifacts = artifacts;
        this.reviews = reviews; this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCaseVersion publish(String workingCopyId, String actor, EvalAdminRole role) {
        requirePublisher(role);
        EvalCaseWorkingCopy workingCopy = workingCopies.get(workingCopyId, actor, role);
        if (workingCopy.getStatus() != EvalCaseWorkingCopyStatus.APPROVED
                && workingCopy.getStatus() != EvalCaseWorkingCopyStatus.PUBLISHED) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION,
                    "only an approved working copy can be published");
        }
        if (workingCopy.getEvaluationTarget() == null
                || workingCopy.getTargetMigrationStatus() == org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus.AMBIGUOUS) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.TARGET_AMBIGUOUS,
                    "evaluationTarget must be confirmed before publication");
        }
        byte[] bytes = content.write(workingCopy.getDefinition());
        String hash = content.sha256(bytes);
        EvalCaseVersion existing = versions.find(workingCopy.getCaseId(), workingCopy.getCaseVersion()).orElse(null);
        if (existing != null) {
            if (!hash.equals(existing.getContentHash())) {
                throw new EvalControlPlaneException(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION,
                        "case version already exists with different content");
            }
            if (workingCopy.getStatus() == EvalCaseWorkingCopyStatus.APPROVED) {
                workingCopies.transition(workingCopyId, EvalCaseWorkingCopyStatus.PUBLISHED, actor, role);
            }
            return existing;
        }
        String artifactRef = artifacts.putIfAbsent(hash, bytes);
        String approvedBy = reviews.list(workingCopyId).stream()
                .filter(review -> "APPROVE".equals(review.getDecision()))
                .max(Comparator.comparing(EvalCaseWorkingCopyReview::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(EvalCaseWorkingCopyReview::getReviewerUserId).orElse(actor);
        EvalCaseVersion published = EvalCaseVersion.builder().caseId(workingCopy.getCaseId())
                .caseVersion(workingCopy.getCaseVersion()).contentHash(hash).artifactRef(artifactRef)
                .evaluationTarget(workingCopy.getEvaluationTarget())
                .targetMigrationStatus(workingCopy.getTargetMigrationStatus())
                .approvedBy(approvedBy).publishedAt(clock.instant()).build();
        versions.insert(published);
        workingCopies.transition(workingCopyId, EvalCaseWorkingCopyStatus.PUBLISHED, actor, role);
        return published;
    }

    public EvalCaseWorkingCopy clonePublished(String caseId, String caseVersion, String newCaseId,
                                              String newCaseVersion, String actor, EvalAdminRole role) {
        EvalCaseVersion source = versions.find(caseId, caseVersion)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "published case version not found"));
        byte[] bytes = artifacts.read(source.getArtifactRef())
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.ARTIFACT_UNAVAILABLE,
                        "published case artifact is unavailable"));
        EvalCaseDefinition definition = content.read(bytes, EvalCaseDefinition.class);
        definition.setCaseId(require(newCaseId, "caseId"));
        definition.setCaseVersion(require(newCaseVersion, "caseVersion"));
        definition.setDatasetVersion("dev-draft");
        return workingCopies.createPublishedClone(definition, actor, role);
    }

    public java.util.List<EvalCaseVersion> list(String caseId) {
        return versions.list(caseId);
    }

    public EvalCaseDefinition load(String caseId, String caseVersion) {
        EvalCaseVersion version = versions.find(caseId, caseVersion)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "published case version not found"));
        return content.read(artifacts.read(version.getArtifactRef())
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.ARTIFACT_UNAVAILABLE,
                        "published case artifact is unavailable")),
                EvalCaseDefinition.class);
    }

    public EvalCaseVersion retire(String caseId, String caseVersion, String actor, EvalAdminRole role) {
        requirePublisher(role);
        EvalCaseVersion current = versions.find(caseId, caseVersion)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "published case version not found"));
        if (current.getRetiredAt() != null) return current;
        if (!versions.retire(caseId, caseVersion, clock.instant())) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.REVISION_CONFLICT,
                    "case version retirement conflict");
        }
        return versions.find(caseId, caseVersion).orElseThrow();
    }

    /** Confirms migration metadata without mutating the immutable published artifact bytes. */
    public EvalCaseVersion confirmTarget(String caseId, String caseVersion, EvaluationTarget target,
                                         String actor, EvalAdminRole role) {
        requirePublisher(role);
        if (target == null) throw new IllegalArgumentException("evaluationTarget is required");
        EvalCaseVersion current = versions.find(caseId, caseVersion)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "published case version not found"));
        EvalCaseDefinition artifactDefinition = content.read(artifacts.read(current.getArtifactRef())
                        .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.ARTIFACT_UNAVAILABLE,
                                "published case artifact is unavailable")),
                EvalCaseDefinition.class);
        // Metadata may fill a missing legacy Target, but must never contradict immutable content.
        if (artifactDefinition.getEvaluationTarget() != null
                && artifactDefinition.getEvaluationTarget() != target) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                    "published Case artifact declares a different evaluationTarget");
        }
        if (current.getEvaluationTarget() != null
                && current.getTargetMigrationStatus() != org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus.AMBIGUOUS) {
            if (current.getEvaluationTarget() != target) {
                throw new EvalControlPlaneException(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                        "confirmed published Case target is immutable");
            }
            return current;
        }
        if (!versions.confirmTarget(caseId, caseVersion, target)) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.REVISION_CONFLICT,
                    "published Case target confirmation conflict");
        }
        return versions.find(caseId, caseVersion).orElseThrow();
    }

    private void requirePublisher(EvalAdminRole role) {
        if (role != EvalAdminRole.ADMIN && role != EvalAdminRole.RELEASE_OWNER) {
            throw new SecurityException("Eval Admin role is required to publish cases");
        }
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
