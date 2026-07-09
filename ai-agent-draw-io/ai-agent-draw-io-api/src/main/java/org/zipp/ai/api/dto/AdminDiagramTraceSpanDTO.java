package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminDiagramTraceSpanDTO {

    private String id;
    private String parentId;
    private String kind;
    private String name;
    private String runId;
    private String requestId;
    private String userId;
    private Long sequenceNo;
    private String eventType;
    private String phase;
    private String status;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
    private String provider;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Double estimatedCost;
    private String toolName;
    private String metadataJson;
    private String errorClass;
    private AdminDiagramEffectDTO diagramEffect;
}
