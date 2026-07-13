package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.EvalCanaryAssessmentPO;
import java.util.List;

@Mapper
public interface IEvalCanaryAssessmentMapper {
    int insert(EvalCanaryAssessmentPO value);
    List<EvalCanaryAssessmentPO> list(@Param("evalRunId") String evalRunId, @Param("limit") int limit);
}
