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
    int mergeCandidateModelEvidence(@Param("candidateId") String candidateId,
                                    @Param("modelVersion") String modelVersion,
                                    @Param("modelConfidence") Double modelConfidence,
                                    @Param("modelEvidenceJson") String modelEvidenceJson,
                                    @Param("evidenceSummary") String evidenceSummary);
    int insertReview(EvalCaseReviewPO review); int insertLineage(EvalCaseLineagePO lineage);
    int insertDraft(EvalCaseDraftPO draft);
    EvalCaseDraftPO selectLatestDraft(@Param("candidateId") String candidateId);
    int upsertCaseHealth(EvalCaseHealthPO health);
    List<EvalCaseHealthPO> selectCaseHealth(@Param("status") String status, @Param("limit") int limit);
    int insertSemanticMinerRun(SemanticMinerRunPO run);
    int updateSemanticMinerRun(SemanticMinerRunPO run);
    SemanticMinerRunPO selectSemanticMinerRun(@Param("runId") String runId);
    List<SemanticMinerRunPO> selectSemanticMinerRuns(@Param("limit") int limit);
}
