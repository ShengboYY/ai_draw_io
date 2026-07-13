package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

/** Per-Trace execution record; failures are isolated at this boundary. */
@Data
@Builder
public class TraceAnalysisItem {
    private String id;
    private String jobId;
    private String sourceRunId;
    private String analyzerType;
    private String status;
    private String outcomeStatus;
    private int attempt;
    private String candidateId;
    private Long latencyMs;
    private double estimatedCost;
    private String errorClass;
    private String errorMessage;
}
