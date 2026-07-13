package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseSourceType;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseWorkingCopyStore;
import org.zipp.ai.infrastructure.dao.IEvalCaseWorkingCopyMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseWorkingCopyPO;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/** MyBatis adapter that keeps JSON serialization outside the domain service. */
@Repository
public class EvalCaseWorkingCopyRepository implements IEvalCaseWorkingCopyStore {
    private final IEvalCaseWorkingCopyMapper mapper;
    private final ObjectMapper objectMapper;

    public EvalCaseWorkingCopyRepository(IEvalCaseWorkingCopyMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<EvalCaseWorkingCopy> find(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String ownerUserId, int limit, int offset) {
        return mapper.selectList(status == null ? null : status.name(), ownerUserId, limit, offset)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void insert(EvalCaseWorkingCopy workingCopy) {
        mapper.insert(toPo(workingCopy));
    }

    @Override
    public boolean update(EvalCaseWorkingCopy workingCopy, long expectedRevision) {
        return mapper.update(toPo(workingCopy), expectedRevision) == 1;
    }

    private EvalCaseWorkingCopy toDomain(EvalCaseWorkingCopyPO po) {
        try {
            return EvalCaseWorkingCopy.builder()
                    .id(po.getId()).caseId(po.getCaseId()).caseVersion(po.getCaseVersion())
                    .sourceType(EvalCaseSourceType.valueOf(po.getSourceType())).candidateId(po.getCandidateId())
                    .status(EvalCaseWorkingCopyStatus.valueOf(po.getStatus())).ownerUserId(po.getOwnerUserId())
                    .revision(po.getRevision())
                    .definition(objectMapper.readValue(po.getDefinitionJson(), EvalCaseDefinition.class))
                    .evaluationTarget(enumValue(EvaluationTarget.class, po.getEvaluationTarget()))
                    .targetMigrationStatus(enumValue(EvaluationTargetMigrationStatus.class, po.getTargetMigrationStatus()))
                    .createdAt(po.getCreatedAt().toInstant()).updatedAt(po.getUpdatedAt().toInstant()).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid Eval Case Working Copy JSON", e);
        }
    }

    private EvalCaseWorkingCopyPO toPo(EvalCaseWorkingCopy value) {
        try {
            EvalCaseWorkingCopyPO po = new EvalCaseWorkingCopyPO();
            po.setId(value.getId()); po.setCaseId(value.getCaseId()); po.setCaseVersion(value.getCaseVersion());
            po.setSourceType(value.getSourceType().name()); po.setCandidateId(value.getCandidateId());
            po.setStatus(value.getStatus().name()); po.setOwnerUserId(value.getOwnerUserId());
            po.setRevision(value.getRevision()); po.setDefinitionJson(objectMapper.writeValueAsString(value.getDefinition()));
            po.setEvaluationTarget(value.getEvaluationTarget() == null ? null : value.getEvaluationTarget().name());
            po.setTargetMigrationStatus(value.getTargetMigrationStatus() == null ? null : value.getTargetMigrationStatus().name());
            po.setCreatedAt(Date.from(value.getCreatedAt())); po.setUpdatedAt(Date.from(value.getUpdatedAt()));
            return po;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Eval Case Working Copy cannot be serialized", e);
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
