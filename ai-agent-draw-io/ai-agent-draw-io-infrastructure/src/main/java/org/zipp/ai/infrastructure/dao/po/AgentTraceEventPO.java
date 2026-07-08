package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class AgentTraceEventPO {

    private String id;
    private String runId;
    private String requestId;
    private String userId;
    private Long sequenceNo;
    private String eventType;
    private String phase;
    private String status;
    private String metadataJson;
    private Date occurredAt;
}
