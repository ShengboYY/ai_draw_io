package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.DiagramConversationMessagePO;

import java.util.List;

@Mapper
public interface IDiagramConversationMapper {

    List<DiagramConversationMessagePO> selectMessages(@Param("userId") String userId,
                                                      @Param("diagramId") String diagramId);

    List<DiagramConversationMessagePO> selectMessagesByScope(@Param("userId") String userId,
                                                              @Param("diagramId") String diagramId,
                                                              @Param("scopeKeys") List<String> scopeKeys);

    int upsertMessage(DiagramConversationMessagePO message);

    int deleteByUserId(@Param("userId") String userId);

}
