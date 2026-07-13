package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyReview;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseWorkingCopyReviewStore;
import org.zipp.ai.infrastructure.dao.IEvalCaseLifecycleMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseWorkingCopyReviewPO;

import java.util.Date;
import java.util.List;

@Repository
public class EvalCaseWorkingCopyReviewRepository implements IEvalCaseWorkingCopyReviewStore {
    private final IEvalCaseLifecycleMapper mapper;

    public EvalCaseWorkingCopyReviewRepository(IEvalCaseLifecycleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(EvalCaseWorkingCopyReview value) {
        EvalCaseWorkingCopyReviewPO po = new EvalCaseWorkingCopyReviewPO();
        po.setId(value.getId()); po.setWorkingCopyId(value.getWorkingCopyId());
        po.setWorkingCopyRevision(value.getWorkingCopyRevision()); po.setReviewerUserId(value.getReviewerUserId());
        po.setDecision(value.getDecision()); po.setReason(value.getReason()); po.setCreatedAt(Date.from(value.getCreatedAt()));
        mapper.insertReview(po);
    }

    @Override
    public List<EvalCaseWorkingCopyReview> list(String workingCopyId) {
        return mapper.selectReviews(workingCopyId).stream().map(po -> EvalCaseWorkingCopyReview.builder()
                .id(po.getId()).workingCopyId(po.getWorkingCopyId()).workingCopyRevision(po.getWorkingCopyRevision())
                .reviewerUserId(po.getReviewerUserId()).decision(po.getDecision()).reason(po.getReason())
                .createdAt(po.getCreatedAt().toInstant()).build()).toList();
    }
}
