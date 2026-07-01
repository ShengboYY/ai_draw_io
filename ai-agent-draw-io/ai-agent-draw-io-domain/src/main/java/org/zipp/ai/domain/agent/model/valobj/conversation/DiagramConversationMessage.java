package org.zipp.ai.domain.agent.model.valobj.conversation;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class DiagramConversationMessage {

    private String userId;
    private String diagramId;
    private String sessionId;
    private String clientMessageId;
    private String role;
    private String content;
    private Date createdAt;
    private Date updatedAt;

}
