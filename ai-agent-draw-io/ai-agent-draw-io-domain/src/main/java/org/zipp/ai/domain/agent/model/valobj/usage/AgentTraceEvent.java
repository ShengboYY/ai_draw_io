package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Cross-cutting lifecycle event metadata; intentionally excludes prompt/canvas content. */
@Data
@Builder
public class AgentTraceEvent {

    private String id;
    private String runId;
    private String requestId;
    private String userId;
    private Long sequenceNo;
    private String eventType;
    private String phase;
    private String status;
    private String metadataJson;
    private Instant occurredAt;
}
