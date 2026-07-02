package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import java.util.List;

@Mapper
public interface ICanvasStateMapper {

    CanvasStatePO selectByUserAndDiagram(@Param("userId") String userId, @Param("diagramId") String diagramId);

    List<CanvasStatePO> selectDiagramsByUser(@Param("userId") String userId);

    List<CanvasStatePO> selectImportableDiagrams(@Param("userId") String userId);

    int upsertDiagram(CanvasStatePO state);

    int insertCanvasState(CanvasStatePO state);

    int updateCanvasStateByVersion(CanvasStatePO state);

    int countCanvasState(@Param("userId") String userId,
                         @Param("diagramId") String diagramId);

    int updateDiagramTitle(@Param("userId") String userId,
                           @Param("diagramId") String diagramId,
                           @Param("title") String title);

    int softDeleteDiagram(@Param("userId") String userId,
                          @Param("diagramId") String diagramId);

    int countDiagramById(@Param("diagramId") String diagramId);

    int insertImportedDiagram(@Param("sourceUserId") String sourceUserId,
                              @Param("sourceDiagramId") String sourceDiagramId,
                              @Param("targetUserId") String targetUserId,
                              @Param("targetDiagramId") String targetDiagramId);

    int insertImportedCanvasState(@Param("sourceUserId") String sourceUserId,
                                  @Param("sourceDiagramId") String sourceDiagramId,
                                  @Param("targetUserId") String targetUserId,
                                  @Param("targetDiagramId") String targetDiagramId);

    int insertImportedConversationMessages(@Param("sourceUserId") String sourceUserId,
                                           @Param("sourceDiagramId") String sourceDiagramId,
                                           @Param("targetUserId") String targetUserId,
                                           @Param("targetDiagramId") String targetDiagramId);

    int redactCanvasStateForUser(@Param("userId") String userId,
                                 @Param("anonymizedUserId") String anonymizedUserId);

    int softDeleteAndAnonymizeUserDiagrams(@Param("userId") String userId,
                                           @Param("anonymizedUserId") String anonymizedUserId);

}
