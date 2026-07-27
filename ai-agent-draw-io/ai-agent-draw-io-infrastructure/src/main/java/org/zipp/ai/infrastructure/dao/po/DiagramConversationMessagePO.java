package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class DiagramConversationMessagePO {

    private Long id;
    private String userId;
    private String diagramId;
    private String turnId;
    private String conversationId;
    private String sessionId;
    private String clientMessageId;
    private String role;
    private String content;
    private Date createdAt;
    private Date updatedAt;

}
