package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class SaveDiagramMessagesRequestDTO {

    private String userId;
    private String sessionId;
    private List<DiagramConversationMessageDTO> messages;

}
