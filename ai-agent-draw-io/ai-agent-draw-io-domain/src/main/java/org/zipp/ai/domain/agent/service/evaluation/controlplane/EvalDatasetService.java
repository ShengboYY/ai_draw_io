package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Owns mutable dataset drafts and freezes exact case-version membership at publication. */
@Service
public class EvalDatasetService {
    private final IEvalDatasetStore datasets;
    private final IEvalCaseVersionStore cases;
    private final Clock clock;
    private final EvalContentSupport content = new EvalContentSupport();

    @Autowired
    public EvalDatasetService(IEvalDatasetStore datasets, IEvalCaseVersionStore cases) {
        this(datasets, cases, Clock.systemUTC());
    }

    public EvalDatasetService(IEvalDatasetStore datasets, IEvalCaseVersionStore cases, Clock clock) {
        this.datasets = datasets; this.cases = cases; this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalDataset create(String name, EvalDatasetClass datasetClass, String actor, EvalAdminRole role) {
        requireAdmin(role); require(name, "name");
        EvalDataset value = EvalDataset.builder().id("eds_" + UUID.randomUUID()).name(name.trim())
                .datasetClass(datasetClass == null ? EvalDatasetClass.DEV : datasetClass)
                .ownerUserId(actor).createdAt(clock.instant()).build();
        datasets.insertDataset(value);
        return value;
    }

    public EvalDatasetVersion createVersion(String datasetId, String version, List<EvalDatasetMember> members,
                                            String actor, EvalAdminRole role) {
        requireAdmin(role); EvalDataset dataset = requireDataset(datasetId); require(version, "version");
        if (datasets.find(datasetId, version).isPresent()) throw error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, "dataset version already exists");
        EvalDatasetVersion value = EvalDatasetVersion.builder().datasetId(datasetId).version(version.trim())
                .datasetClass(dataset.getDatasetClass()).status(EvalDatasetVersionStatus.DRAFT).revision(1L)
                .members(normalize(members)).build();
        datasets.insert(value); return value;
    }

    public EvalDatasetVersion replaceMembers(String datasetId, String version, long expectedRevision,
                                             List<EvalDatasetMember> members, String actor, EvalAdminRole role) {
        requireAdmin(role); EvalDatasetVersion current = requireVersion(datasetId, version);
        if (current.getStatus() != EvalDatasetVersionStatus.DRAFT) {
            throw error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, "only a draft dataset version can be edited");
        }
        EvalDatasetVersion updated = current.toBuilder().members(normalize(members))
                .revision(expectedRevision + 1).contentHash(null).build();
        if (!datasets.update(updated, expectedRevision)) throw error(EvalControlPlaneErrorCode.REVISION_CONFLICT, "dataset revision conflict");
        return updated;
    }

    public EvalDatasetVersion validate(String datasetId, String version, String actor, EvalAdminRole role) {
        requireAdmin(role); EvalDatasetVersion current = requireVersion(datasetId, version);
        if (current.getStatus() != EvalDatasetVersionStatus.DRAFT) throw error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, "dataset is not a draft");
        if (current.getMembers().isEmpty()) throw error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, "dataset requires at least one case");
        EvaluationTarget memberTarget = null;
        for (EvalDatasetMember member : current.getMembers()) {
            EvalCaseVersion caseVersion = cases.find(member.getCaseId(), member.getCaseVersion())
                    .orElseThrow(() -> error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION,
                            "dataset member is not a published case: " + member.getCaseId() + "@" + member.getCaseVersion()));
            if (caseVersion.getRetiredAt() != null) throw error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, "dataset member is retired");
            if (caseVersion.getEvaluationTarget() == null
                    || caseVersion.getTargetMigrationStatus() == EvaluationTargetMigrationStatus.AMBIGUOUS) {
                throw error(EvalControlPlaneErrorCode.TARGET_AMBIGUOUS,
                        "dataset member target requires confirmation: " + member.getCaseId());
            }
            if (memberTarget == null) memberTarget = caseVersion.getEvaluationTarget();
            else if (memberTarget != caseVersion.getEvaluationTarget()) {
                throw error(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                        "all dataset members must share one evaluationTarget");
            }
        }
        EvalDataset dataset = requireDataset(datasetId);
        if (dataset.getEvaluationTarget() != null && dataset.getEvaluationTarget() != memberTarget) {
            throw error(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                    "dataset target cannot change between versions");
        }
        EvalDatasetVersion validated = current.toBuilder().evaluationTarget(memberTarget)
                .status(EvalDatasetVersionStatus.VALIDATED)
                .revision(current.getRevision() + 1).build();
        if (!datasets.validateWithTarget(validated, current.getRevision(), memberTarget)) {
            throw error(EvalControlPlaneErrorCode.REVISION_CONFLICT, "dataset validation conflict");
        }
        return validated;
    }

    public EvalDatasetVersion publish(String datasetId, String version, String actor, EvalAdminRole role) {
        requireAdmin(role); EvalDatasetVersion current = requireVersion(datasetId, version);
        if (current.getStatus() == EvalDatasetVersionStatus.PUBLISHED) return current;
        if (current.getStatus() != EvalDatasetVersionStatus.VALIDATED) throw error(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, "dataset must be validated before publish");
        String hash = content.sha256(content.write(current.getMembers()));
        EvalDatasetVersion published = current.toBuilder().status(EvalDatasetVersionStatus.PUBLISHED)
                .contentHash(hash).revision(current.getRevision() + 1).publishedBy(actor)
                .publishedAt(clock.instant()).build();
        if (!datasets.update(published, current.getRevision())) throw error(EvalControlPlaneErrorCode.REVISION_CONFLICT, "dataset revision conflict");
        return published;
    }

    public EvalDatasetVersion cloneVersion(String sourceDatasetId, String sourceVersion, String targetDatasetId,
                                           String targetVersion, String actor, EvalAdminRole role) {
        EvalDatasetVersion source = requireVersion(sourceDatasetId, sourceVersion);
        return createVersion(targetDatasetId, targetVersion, source.getMembers(), actor, role);
    }

    public List<EvalDataset> list() { return datasets.listDatasets(); }
    public List<EvalDatasetVersion> versions(String datasetId) { return datasets.listVersions(datasetId); }
    public EvalDatasetVersion get(String datasetId, String version) { return requireVersion(datasetId, version); }

    private List<EvalDatasetMember> normalize(List<EvalDatasetMember> members) {
        if (members == null) return List.of();
        return members.stream().peek(member -> {
            if (member == null || blank(member.getCaseId()) || blank(member.getCaseVersion())) {
                throw new IllegalArgumentException("dataset member requires caseId and caseVersion");
            }
        }).distinct().sorted(Comparator.comparing(EvalDatasetMember::getCaseId)
                .thenComparing(EvalDatasetMember::getCaseVersion)).toList();
    }

    private EvalDataset requireDataset(String id) { return datasets.findDataset(id).orElseThrow(() -> error(EvalControlPlaneErrorCode.NOT_FOUND, "dataset not found")); }
    private EvalDatasetVersion requireVersion(String id, String version) { return datasets.find(id, version).orElseThrow(() -> error(EvalControlPlaneErrorCode.NOT_FOUND, "dataset version not found")); }
    private void requireAdmin(EvalAdminRole role) { if (role != EvalAdminRole.ADMIN && role != EvalAdminRole.RELEASE_OWNER) throw new SecurityException("Eval Admin role is required"); }
    private EvalControlPlaneException error(EvalControlPlaneErrorCode code, String message) {
        return new EvalControlPlaneException(code, message);
    }
    private void require(String value, String field) { if (blank(value)) throw new IllegalArgumentException(field + " is required"); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
