package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ChatResponseDTO {

    private String type;
    private String content;
    private String requestId;
    private String runId;

}
