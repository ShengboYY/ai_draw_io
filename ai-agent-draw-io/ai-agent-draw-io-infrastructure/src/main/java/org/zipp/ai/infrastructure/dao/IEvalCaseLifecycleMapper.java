package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseEvidencePO;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseWorkingCopyReviewPO;

import java.util.List;

@Mapper
public interface IEvalCaseLifecycleMapper {
    int insertEvidence(EvalCaseEvidencePO evidence);
    List<EvalCaseEvidencePO> selectEvidence(@Param("workingCopyId") String workingCopyId);
    int insertReview(EvalCaseWorkingCopyReviewPO review);
    List<EvalCaseWorkingCopyReviewPO> selectReviews(@Param("workingCopyId") String workingCopyId);
}
