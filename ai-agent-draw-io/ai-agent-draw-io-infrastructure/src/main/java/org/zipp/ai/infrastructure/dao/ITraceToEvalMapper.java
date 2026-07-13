package org.zipp.ai.infrastructure.dao;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.*;
import java.util.List;
@Mapper
public interface ITraceToEvalMapper {
    EvalCaseCandidatePO selectCandidate(@Param("candidateId") String candidateId);
    EvalCaseCandidatePO selectCandidateBySource(@Param("sourceRunId") String sourceRunId, @Param("failureFamily") String failureFamily);
    List<EvalCaseCandidatePO> selectCandidates(@Param("status") String status, @Param("risk") String risk,
                                               @Param("limit") int limit, @Param("offset") int offset);
    int insertCandidate(EvalCaseCandidatePO candidate); int updateCandidateStatus(@Param("candidateId") String candidateId, @Param("status") String status);
    int insertReview(EvalCaseReviewPO review); int insertLineage(EvalCaseLineagePO lineage);
    int insertDraft(EvalCaseDraftPO draft);
    EvalCaseDraftPO selectLatestDraft(@Param("candidateId") String candidateId);
    int upsertCaseHealth(EvalCaseHealthPO health);
}
