package org.zipp.ai.infrastructure.dao.grounding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerCommitPort;

@Mapper
public interface IEvidenceAnswerCommitMapper {
    GroundedRunRowPO lockRunState(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan);
    AnswerCanvasTupleRowPO lockCanvasTuple(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan);
    int insertMessage(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan);
    int insertCitation(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan,
                       @Param("citation") EvidenceAnswerCommitPort.CitationWrite citation,
                       @Param("canvasVersion") long canvasVersion);
    int insertCitationEvidence(@Param("citationId") String citationId,
                               @Param("link") EvidenceAnswerCommitPort.EvidenceLink link);

    String lockValidEvidenceLink(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan,
                                 @Param("link") EvidenceAnswerCommitPort.EvidenceLink link);
    int upsertSourcePin(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan,
                        @Param("link") EvidenceAnswerCommitPort.EvidenceLink link);
    int completeRun(@Param("plan") EvidenceAnswerCommitPort.CommitPlan plan);
}
