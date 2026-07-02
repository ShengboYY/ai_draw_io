package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class AdminUsageDimensionDTO {

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
