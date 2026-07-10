package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminLlmCallDTO {

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
    private String providerRequestId;
    private String providerResponseId;
    private Long ttftMs;
    private Integer attemptCount;
    private Integer retryCount;
    private String status;
    private String errorClass;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
}
