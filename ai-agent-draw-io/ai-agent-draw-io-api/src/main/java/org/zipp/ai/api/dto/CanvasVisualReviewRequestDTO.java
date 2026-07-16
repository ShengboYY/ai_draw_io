package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class CanvasVisualReviewRequestDTO {

    private String userId;
    private String agentId;
    private String sessionId;
    private String modelCredentialId;
    private String requestId;
    private String sourceRunId;
    private String parentRunId;
    private Integer visualRepairRound;
    private String diagramId;
    private Long expectedVersion;
    private String beforeContentHash;
    private Boolean shadow;
    private String expectedContentHash;
    private String originalUserTask;
    private String diagramType;
    private String stage;
    private String beforeImageDataUrl;
    private String afterImageDataUrl;
    private String rendererVersion;
}
