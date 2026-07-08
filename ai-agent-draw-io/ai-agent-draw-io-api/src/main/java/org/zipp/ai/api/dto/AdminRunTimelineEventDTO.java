package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminRunTimelineEventDTO {

    private String id;
    private String source;
    private String runId;
    private String requestId;
    private String userId;
    private Long sequenceNo;
    private String eventType;
    private String phase;
    private String status;
    private String detail;
    private String metadataJson;
    private Instant occurredAt;
    private Long latencyMs;
}
