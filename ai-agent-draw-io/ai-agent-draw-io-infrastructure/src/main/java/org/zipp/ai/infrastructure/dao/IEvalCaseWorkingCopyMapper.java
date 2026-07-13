package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseWorkingCopyPO;

import java.util.List;

@Mapper
public interface IEvalCaseWorkingCopyMapper {
    EvalCaseWorkingCopyPO selectById(@Param("id") String id);

    EvalCaseWorkingCopyPO selectByCandidateId(@Param("candidateId") String candidateId);

    List<EvalCaseWorkingCopyPO> selectList(@Param("status") String status,
                                           @Param("ownerUserId") String ownerUserId,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    int insert(EvalCaseWorkingCopyPO workingCopy);

    int insertTraceDraftIfAbsent(EvalCaseWorkingCopyPO workingCopy);

    int update(@Param("workingCopy") EvalCaseWorkingCopyPO workingCopy,
               @Param("expectedRevision") long expectedRevision);
}
