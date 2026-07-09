package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class AdminDiagramTraceSummaryDTO {

    private String status;
    private String outcome;
    private String runId;
    private String requestId;
    private String userId;
    private String sessionId;
    private String diagramId;
    private String agentId;
    private String requestType;
    private Long latencyMs;
    private Long llmCallCount;
    private Long toolCallCount;
    private Long eventCount;
    private Long spanCount;
    private Long totalTokens;
    private Double estimatedCost;
}
