package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class DiagramConversationMessageDTO {

    private String clientMessageId;
    private String turnId;
    private String sessionId;
    private String role;
    private String content;
    private List<String> attachmentRefs;
    private Date createdAt;
    private List<EvidenceClaimDTO> evidenceClaims;
    private List<EvidenceSourceDTO> evidenceSources;

    @Data
    public static class EvidenceClaimDTO {
        private String claimKey;
        private List<String> citationKeys;
        private String supportType;
    }

    @Data
    public static class EvidenceSourceDTO {
        private String citationKey;
        private String sourceLabel;
        private Integer pageNumber;
        private String modality;
        private String origin;
    }

}
