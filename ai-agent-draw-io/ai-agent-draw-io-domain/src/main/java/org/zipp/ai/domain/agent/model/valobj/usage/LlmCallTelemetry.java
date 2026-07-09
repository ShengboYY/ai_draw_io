package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Provider usage metadata for one model call; token counts stay null when providers omit them. */
@Data
@Builder
public class LlmCallTelemetry {

    private String id;
    private String runId;
    private String parentId;
    private String userId;
    private String phase;
    private String provider;
    private String model;
    private String credentialSource;
    private String modelCredentialId;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String status;
    private String errorClass;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
}
