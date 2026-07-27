package org.zipp.ai.infrastructure.dao.grounding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

@Mapper
public interface IGroundedCanvasCommitMapper {
    java.util.List<CellProvenanceTypeRowPO> selectPersistedProvenance(
            @Param("query") GroundedCanvasCommitPort.InheritanceQuery query);
    GroundedRunRowPO lockRunState(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan);
    int updateExistingCanvas(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan);
    int insertNewCanvas(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan);
    CanvasStatePO selectCommittedCanvas(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan);
    int insertCanvasVersion(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan,
                            @Param("canvasVersion") long canvasVersion);
    int copyInheritedProvenance(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan,
                                @Param("previousVersion") long previousVersion,
                                @Param("canvasVersion") long canvasVersion);
    int insertCitation(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan,
                       @Param("citation") GroundedCanvasCommitPort.CitationWrite citation,
                       @Param("canvasVersion") long canvasVersion);
    int insertCitationEvidence(@Param("citationId") String citationId,
                               @Param("link") GroundedCanvasCommitPort.EvidenceLink link);
    int upsertCellProvenance(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan,
                             @Param("citation") GroundedCanvasCommitPort.CitationWrite citation,
                             @Param("canvasVersion") long canvasVersion);
    int upsertSourcePin(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan,
                        @Param("link") GroundedCanvasCommitPort.EvidenceLink link);
    int supersedeUnusedPins(@Param("diagramId") String diagramId,
                            @Param("canvasVersion") long canvasVersion);
    int completeRun(@Param("plan") GroundedCanvasCommitPort.CommitPlan plan);
}
