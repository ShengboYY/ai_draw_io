package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ChatResponseDTO {

    private String type;
    private String content;
    private String requestId;
    private String runId;
    private java.util.List<TargetCandidateDTO> targetCandidates;
    private Long canvasVersion;
    private String contentHash;

    @Data
    public static class TargetCandidateDTO {
        private String cellId;
        private String kind;
        private String shortLabel;
        private String reasonCode;
    }

}
