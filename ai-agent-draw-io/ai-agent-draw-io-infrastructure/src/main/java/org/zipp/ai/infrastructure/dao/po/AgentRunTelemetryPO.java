package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class AgentRunTelemetryPO {

    private String id;
    private String userId;
    private String agentId;
    private String sessionId;
    private String requestType;
    private String credentialSource;
    private String modelCredentialId;
    private String status;
    private String errorClass;
    private Date startedAt;
    private Date completedAt;
    private Long latencyMs;
}
