package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseEvidence;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseEvidenceType;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseEvidenceStore;
import org.zipp.ai.infrastructure.dao.IEvalCaseLifecycleMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseEvidencePO;

import java.util.Date;
import java.util.List;

@Repository
public class EvalCaseEvidenceRepository implements IEvalCaseEvidenceStore {
    private final IEvalCaseLifecycleMapper mapper;

    public EvalCaseEvidenceRepository(IEvalCaseLifecycleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(EvalCaseEvidence value) {
        EvalCaseEvidencePO po = new EvalCaseEvidencePO();
        po.setId(value.getId()); po.setWorkingCopyId(value.getWorkingCopyId());
        po.setWorkingCopyRevision(value.getWorkingCopyRevision()); po.setEvidenceType(value.getType().name());
        po.setStatus(value.getStatus()); po.setPayloadJson(value.getPayloadJson());
        po.setComponentVersion(value.getComponentVersion()); po.setCreatedAt(Date.from(value.getCreatedAt()));
        mapper.insertEvidence(po);
    }

    @Override
    public List<EvalCaseEvidence> list(String workingCopyId) {
        return mapper.selectEvidence(workingCopyId).stream().map(po -> EvalCaseEvidence.builder()
                .id(po.getId()).workingCopyId(po.getWorkingCopyId()).workingCopyRevision(po.getWorkingCopyRevision())
                .type(EvalCaseEvidenceType.valueOf(po.getEvidenceType())).status(po.getStatus())
                .payloadJson(po.getPayloadJson()).componentVersion(po.getComponentVersion())
                .createdAt(po.getCreatedAt().toInstant()).build()).toList();
    }
}
