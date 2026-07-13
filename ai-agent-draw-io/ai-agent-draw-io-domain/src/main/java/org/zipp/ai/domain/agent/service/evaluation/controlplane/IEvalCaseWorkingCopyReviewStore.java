package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyReview;

import java.util.List;

public interface IEvalCaseWorkingCopyReviewStore {
    void insert(EvalCaseWorkingCopyReview review);
    List<EvalCaseWorkingCopyReview> list(String workingCopyId);
}
