package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class UsageDimensionSummaryPO {

    private String provider;
    private String model;
    private String credentialSource;
    private Long llmCallCount;
    private Long successfulCallCount;
    private Long failedCallCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long unknownTokenCallCount;
    private Long averageLatencyMs;
}
