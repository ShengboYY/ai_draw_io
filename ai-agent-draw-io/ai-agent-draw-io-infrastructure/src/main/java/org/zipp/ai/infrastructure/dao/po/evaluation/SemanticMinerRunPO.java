package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;

import java.util.Date;

@Data
public class SemanticMinerRunPO {
    private String id;
    private String status;
    private String samplingPolicy;
    private Integer requestedLimit;
    private Integer sampledCount;
    private Integer analyzedCount;
    private Integer candidateCount;
    private Integer errorCount;
    private Double estimatedCostUsd;
    private String modelVersion;
    private String sanitizerVersion;
    private String availabilityReason;
    private String createdBy;
    private Date createdAt;
    private Date startedAt;
    private Date completedAt;
}
