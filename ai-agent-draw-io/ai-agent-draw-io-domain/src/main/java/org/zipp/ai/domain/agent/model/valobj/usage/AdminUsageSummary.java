package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

/** Global metadata-only usage summary for admin operations. */
@Data
@Builder
public class AdminUsageSummary {

    @Builder.Default
    private Long requestCount = 0L;
    @Builder.Default
    private Long successfulRequestCount = 0L;
    @Builder.Default
    private Long failedRequestCount = 0L;
    @Builder.Default
    private Long runningRequestCount = 0L;
    @Builder.Default
    private Long llmCallCount = 0L;
    @Builder.Default
    private Long successfulLlmCallCount = 0L;
    @Builder.Default
    private Long failedLlmCallCount = 0L;
    @Builder.Default
    private Long toolCallCount = 0L;
    @Builder.Default
    private Long successfulToolCallCount = 0L;
    @Builder.Default
    private Long failedToolCallCount = 0L;
    @Builder.Default
    private Long promptTokens = 0L;
    @Builder.Default
    private Long completionTokens = 0L;
    @Builder.Default
    private Long totalTokens = 0L;
    @Builder.Default
    private Long unknownTokenLlmCallCount = 0L;
    @Builder.Default
    private Long averageRunLatencyMs = 0L;
    @Builder.Default
    private Long maxRunLatencyMs = 0L;

    public static AdminUsageSummary empty() {
        return AdminUsageSummary.builder().build();
    }
}
