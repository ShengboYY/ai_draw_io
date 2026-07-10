package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class LlmCallTelemetryPO {

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
    private Date startedAt;
    private Date completedAt;
    private Long latencyMs;
}
