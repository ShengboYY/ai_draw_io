package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;

@Data
public class TraceAnalysisItemPO {
    private String id; private String jobId; private String sourceRunId; private String analyzerType;
    private String status; private String outcomeStatus; private Integer attempt; private String candidateId; private Long latencyMs;
    private Double estimatedCost; private String errorClass; private String errorMessage;
}
