package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCandidatePromotionLink;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCandidatePromotionLinkStore;
import org.zipp.ai.infrastructure.dao.IEvalCandidatePromotionLinkMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCandidatePromotionLinkPO;

import java.util.Date;
import java.util.Optional;

/** Keeps restricted promotion lineage out of public Case persistence. */
@Repository
public class EvalCandidatePromotionLinkRepository implements IEvalCandidatePromotionLinkStore {
    private final IEvalCandidatePromotionLinkMapper mapper;

    public EvalCandidatePromotionLinkRepository(IEvalCandidatePromotionLinkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<EvalCandidatePromotionLink> findByCandidateId(String candidateId) {
        return Optional.ofNullable(mapper.selectByCandidateId(candidateId)).map(this::toDomain);
    }

    @Override
    public Optional<EvalCandidatePromotionLink> findByWorkingCopyId(String workingCopyId) {
        return Optional.ofNullable(mapper.selectByWorkingCopyId(workingCopyId)).map(this::toDomain);
    }

    @Override
    @Transactional
    public EvalCandidatePromotionLink insertIfAbsent(EvalCandidatePromotionLink link) {
        mapper.insertIfAbsent(toPo(link));
        return findByCandidateId(link.getCandidateId())
                .orElseThrow(() -> new IllegalStateException("canonical promotion link was not persisted"));
    }

    private EvalCandidatePromotionLink toDomain(EvalCandidatePromotionLinkPO po) {
        return EvalCandidatePromotionLink.builder().candidateId(po.getCandidateId())
                .workingCopyId(po.getWorkingCopyId()).caseId(po.getCaseId()).caseVersion(po.getCaseVersion())
                .promotedAt(po.getPromotedAt().toInstant())
                .retentionExpiresAt(po.getRetentionExpiresAt() == null ? null : po.getRetentionExpiresAt().toInstant())
                .build();
    }

    private EvalCandidatePromotionLinkPO toPo(EvalCandidatePromotionLink value) {
        EvalCandidatePromotionLinkPO po = new EvalCandidatePromotionLinkPO();
        po.setCandidateId(value.getCandidateId());
        po.setWorkingCopyId(value.getWorkingCopyId());
        po.setCaseId(value.getCaseId());
        po.setCaseVersion(value.getCaseVersion());
        po.setPromotedAt(Date.from(value.getPromotedAt()));
        po.setRetentionExpiresAt(value.getRetentionExpiresAt() == null ? null : Date.from(value.getRetentionExpiresAt()));
        return po;
    }
}
