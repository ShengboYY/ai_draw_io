package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Metadata-only record for one user-visible agent request. */
@Data
@Builder
public class AgentRunTelemetry {

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
