package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseVersionStore;
import org.zipp.ai.infrastructure.dao.IEvalCatalogMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseVersionPO;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class EvalCaseVersionRepository implements IEvalCaseVersionStore {
    private final IEvalCatalogMapper mapper;
    public EvalCaseVersionRepository(IEvalCatalogMapper mapper) { this.mapper = mapper; }
    @Override public void insert(EvalCaseVersion value) { mapper.insertCaseVersion(toPo(value)); }
    @Override public Optional<EvalCaseVersion> find(String caseId, String version) { return Optional.ofNullable(mapper.selectCaseVersion(caseId, version)).map(this::toDomain); }
    @Override public List<EvalCaseVersion> list(String caseId) { return mapper.selectCaseVersions(caseId).stream().map(this::toDomain).toList(); }
    @Override public boolean retire(String caseId, String version, java.time.Instant retiredAt) { return mapper.retireCaseVersion(caseId, version, Date.from(retiredAt)) == 1; }
    @Override public boolean confirmTarget(String caseId, String version, EvaluationTarget target) { return mapper.confirmCaseVersionTarget(caseId, version, target.name()) == 1; }
    private EvalCaseVersion toDomain(EvalCaseVersionPO po) { return EvalCaseVersion.builder().caseId(po.getCaseId()).caseVersion(po.getCaseVersion()).contentHash(po.getContentHash()).artifactRef(po.getArtifactRef()).evaluationTarget(po.getEvaluationTarget() == null ? null : EvaluationTarget.valueOf(po.getEvaluationTarget())).targetMigrationStatus(po.getTargetMigrationStatus() == null ? null : EvaluationTargetMigrationStatus.valueOf(po.getTargetMigrationStatus())).approvedBy(po.getApprovedBy()).publishedAt(po.getPublishedAt().toInstant()).retiredAt(po.getRetiredAt() == null ? null : po.getRetiredAt().toInstant()).build(); }
    private EvalCaseVersionPO toPo(EvalCaseVersion value) { EvalCaseVersionPO po = new EvalCaseVersionPO(); po.setCaseId(value.getCaseId()); po.setCaseVersion(value.getCaseVersion()); po.setContentHash(value.getContentHash()); po.setArtifactRef(value.getArtifactRef()); po.setEvaluationTarget(value.getEvaluationTarget() == null ? null : value.getEvaluationTarget().name()); po.setTargetMigrationStatus(value.getTargetMigrationStatus() == null ? null : value.getTargetMigrationStatus().name()); po.setApprovedBy(value.getApprovedBy()); po.setPublishedAt(Date.from(value.getPublishedAt())); po.setRetiredAt(value.getRetiredAt() == null ? null : Date.from(value.getRetiredAt())); return po; }
}
