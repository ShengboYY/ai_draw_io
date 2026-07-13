package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/** One independently retryable case/repetition execution. */
@Value
@Builder
public class EvalEpisode {
    String id;
    String evalRunId;
    String caseId;
    String caseVersion;
    int repetition;
    EvalEpisodeStatus status;
    String traceRef;
    @Singular List<String> artifactRefs;
    long latencyMs;
    long inputTokens;
    long outputTokens;
    double estimatedCost;
}
