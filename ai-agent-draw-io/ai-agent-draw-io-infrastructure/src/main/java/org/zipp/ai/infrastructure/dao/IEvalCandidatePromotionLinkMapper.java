package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCandidatePromotionLinkPO;

@Mapper
public interface IEvalCandidatePromotionLinkMapper {
    EvalCandidatePromotionLinkPO selectByCandidateId(@Param("candidateId") String candidateId);

    EvalCandidatePromotionLinkPO selectByWorkingCopyId(@Param("workingCopyId") String workingCopyId);

    int insertIfAbsent(EvalCandidatePromotionLinkPO link);
}
