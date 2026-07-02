package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AdminUsageSummaryPO {

    private Long requestCount;
    private Long successfulRequestCount;
    private Long failedRequestCount;
    private Long runningRequestCount;
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
}
