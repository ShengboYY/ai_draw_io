package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalDatasetStore;
import org.zipp.ai.infrastructure.dao.IEvalCatalogMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.*;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class EvalDatasetRepository implements IEvalDatasetStore {
    private final IEvalCatalogMapper mapper;
    public EvalDatasetRepository(IEvalCatalogMapper mapper) { this.mapper = mapper; }
    @Override public void insertDataset(EvalDataset value) { EvalDatasetPO po = new EvalDatasetPO(); po.setId(value.getId()); po.setName(value.getName()); po.setDatasetClass(value.getDatasetClass().name()); po.setOwnerUserId(value.getOwnerUserId()); po.setCreatedAt(Date.from(value.getCreatedAt())); mapper.insertDataset(po); }
    @Override public Optional<EvalDataset> findDataset(String id) { return Optional.ofNullable(mapper.selectDataset(id)).map(po -> EvalDataset.builder().id(po.getId()).name(po.getName()).datasetClass(EvalDatasetClass.valueOf(po.getDatasetClass())).ownerUserId(po.getOwnerUserId()).createdAt(po.getCreatedAt().toInstant()).build()); }
    @Override public List<EvalDataset> listDatasets() { return mapper.selectDatasets().stream().map(po -> EvalDataset.builder().id(po.getId()).name(po.getName()).datasetClass(EvalDatasetClass.valueOf(po.getDatasetClass())).ownerUserId(po.getOwnerUserId()).createdAt(po.getCreatedAt().toInstant()).build()).toList(); }
    @Override @Transactional public void insert(EvalDatasetVersion value) { mapper.insertDatasetVersion(toPo(value)); insertMembers(value); }
    @Override @Transactional public boolean update(EvalDatasetVersion value, long revision) { if (mapper.updateDatasetVersion(toPo(value), revision) != 1) return false; mapper.deleteDatasetMembers(value.getDatasetId(), value.getVersion()); insertMembers(value); return true; }
    @Override public Optional<EvalDatasetVersion> find(String id, String version) { return Optional.ofNullable(mapper.selectDatasetVersion(id, version)).map(this::toDomain); }
    @Override public List<EvalDatasetVersion> listVersions(String id) { return mapper.selectDatasetVersions(id).stream().map(this::toDomain).toList(); }
    private void insertMembers(EvalDatasetVersion value) { for (EvalDatasetMember member : value.getMembers()) { EvalDatasetMemberPO po = new EvalDatasetMemberPO(); po.setDatasetId(value.getDatasetId()); po.setDatasetVersion(value.getVersion()); po.setCaseId(member.getCaseId()); po.setCaseVersion(member.getCaseVersion()); mapper.insertDatasetMember(po); } }
    private EvalDatasetVersion toDomain(EvalDatasetVersionPO po) { List<EvalDatasetMember> members = mapper.selectDatasetMembers(po.getDatasetId(), po.getVersion()).stream().map(member -> EvalDatasetMember.builder().caseId(member.getCaseId()).caseVersion(member.getCaseVersion()).build()).toList(); return EvalDatasetVersion.builder().datasetId(po.getDatasetId()).version(po.getVersion()).datasetClass(EvalDatasetClass.valueOf(po.getDatasetClass())).status(EvalDatasetVersionStatus.valueOf(po.getStatus())).contentHash(po.getContentHash()).revision(po.getRevision()).members(members).publishedBy(po.getPublishedBy()).publishedAt(po.getPublishedAt() == null ? null : po.getPublishedAt().toInstant()).build(); }
    private EvalDatasetVersionPO toPo(EvalDatasetVersion value) { EvalDatasetVersionPO po = new EvalDatasetVersionPO(); po.setDatasetId(value.getDatasetId()); po.setVersion(value.getVersion()); po.setDatasetClass(value.getDatasetClass().name()); po.setStatus(value.getStatus().name()); po.setContentHash(value.getContentHash()); po.setRevision(value.getRevision()); po.setPublishedBy(value.getPublishedBy()); po.setPublishedAt(value.getPublishedAt() == null ? null : Date.from(value.getPublishedAt())); return po; }
}
