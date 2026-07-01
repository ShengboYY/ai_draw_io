package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.Date;

@Data
public class DiagramConversationMessageDTO {

    private String clientMessageId;
    private String sessionId;
    private String role;
    private String content;
    private Date createdAt;

}
