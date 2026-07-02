package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

/** Provider/model/credential-source aggregate; contains no prompt text or key material. */
@Data
@Builder
public class UsageDimensionSummary {

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
