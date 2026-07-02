package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdminUsageDashboardDTO {

    private Long requestCount;
    private Long successfulRequestCount;
    private Long failedRequestCount;
    private Long runningRequestCount;
    private Double requestSuccessRate;
    private Double requestFailureRate;
    private Long llmCallCount;
    private Long successfulLlmCallCount;
    private Long failedLlmCallCount;
    private Long toolCallCount;
    private Long successfulToolCallCount;
    private Long failedToolCallCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long unknownTokenLlmCallCount;
    private Long averageRunLatencyMs;
    private Long maxRunLatencyMs;
    private List<AdminUsageDimensionDTO> groups;
}
