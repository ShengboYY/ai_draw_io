package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService;

import java.time.Instant;
import java.util.List;

/** Aggregate-only canary recommendation. It contains no request, user, trace, prompt, or payload. */
@Value @Builder
public class EvalCanaryAssessment {
    String id;
    String evalRunId;
    String deploymentRef;
    String policyVersion;
    EvalCanaryService.Outcome outcome;
    List<String> reasons;
    int baselineRequests;
    int canaryRequests;
    int canaryFailures;
    int criticalFindings;
    int infrastructureErrors;
    double p95LatencyMs;
    double averageCost;
    String createdBy;
    Instant createdAt;
}
