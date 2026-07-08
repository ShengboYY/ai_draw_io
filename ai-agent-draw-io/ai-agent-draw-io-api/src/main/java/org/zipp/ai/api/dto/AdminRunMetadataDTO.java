package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminRunMetadataDTO {

    private String id;
    private String requestId;
    private String userId;
    private String agentId;
    private String sessionId;
    private String requestType;
    private String credentialSource;
    private String modelCredentialId;
    private String status;
    private String errorClass;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
}
