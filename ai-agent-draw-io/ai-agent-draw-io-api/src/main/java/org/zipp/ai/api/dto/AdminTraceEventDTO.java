package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminTraceEventDTO {

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
