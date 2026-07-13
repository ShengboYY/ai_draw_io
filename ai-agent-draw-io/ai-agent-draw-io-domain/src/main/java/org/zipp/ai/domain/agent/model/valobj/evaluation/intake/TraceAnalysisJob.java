package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Persisted aggregate for one bounded Trace Analysis request. */
@Data
@Builder
public class TraceAnalysisJob {
    private String id;
    private String scope;
    private String analyzerType;
    private String analyzerVersion;
    private String analyzerConfigHash;
    private String sampleDefinitionJson;
    private Instant traceSnapshotAt;
    private String idempotencyKey;
    private String status;
    private int totalItems;
    private int succeededItems;
    private int failedItems;
    private double reservedCost;
    private double actualCost;
    private String createdBy;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
}
