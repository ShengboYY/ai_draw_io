package org.zipp.ai.domain.agent.model.valobj.conversation;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class DiagramConversationMessage {

    private String userId;
    private String diagramId;
    // V2 uses this durable key to return the exact terminal message for a retried turn.
    private String turnId;
    private String sessionId;
    private String clientMessageId;
    private String role;
    private String content;
    @Builder.Default
    private List<String> attachmentRefs = List.of();
    private Date createdAt;
    private Date updatedAt;

}
