package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.DiagramConversationMessagePO;

import java.util.List;

@Mapper
public interface IDiagramConversationMapper {

    List<DiagramConversationMessagePO> selectMessages(@Param("userId") String userId,
                                                      @Param("diagramId") String diagramId);

    int upsertMessage(DiagramConversationMessagePO message);

}
