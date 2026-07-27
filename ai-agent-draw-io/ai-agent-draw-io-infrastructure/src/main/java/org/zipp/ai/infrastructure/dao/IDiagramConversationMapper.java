package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.DiagramConversationMessagePO;
import org.zipp.ai.infrastructure.dao.po.ConversationMessageAttachmentPO;

import java.util.List;

@Mapper
public interface IDiagramConversationMapper {

    List<DiagramConversationMessagePO> selectMessages(@Param("userId") String userId,
                                                      @Param("diagramId") String diagramId);

    List<DiagramConversationMessagePO> selectMessagesByScope(@Param("userId") String userId,
                                                              @Param("diagramId") String diagramId,
                                                              @Param("scopeKeys") List<String> scopeKeys);

    DiagramConversationMessagePO selectAssistantMessageByTurn(
            @Param("userId") String userId,
            @Param("diagramId") String diagramId,
            @Param("conversationId") String conversationId,
            @Param("turnId") String turnId);

    List<ConversationMessageAttachmentPO> selectAttachmentsByMessages(
            @Param("userId") String userId,
            @Param("diagramId") String diagramId,
            @Param("messageIds") List<Long> messageIds);

    int upsertMessage(DiagramConversationMessagePO message);

    int insertMessageAttachment(@Param("userId") String userId,
                                @Param("diagramId") String diagramId,
                                @Param("messageId") Long messageId,
                                @Param("attachmentOrder") int attachmentOrder,
                                @Param("fileRef") String fileRef);

    int deleteByUserId(@Param("userId") String userId);

}
