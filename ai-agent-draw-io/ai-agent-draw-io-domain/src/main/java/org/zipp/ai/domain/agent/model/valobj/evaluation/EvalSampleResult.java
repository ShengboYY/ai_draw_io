package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.Builder;
import lombok.Data;

/** One @1 episode sample; repeated rows estimate the success probability for one case. */
@Data
@Builder
public class EvalSampleResult {
    private String caseId;
    private String slice;
    private int repetition;
    private EvalHarnessResult.Status status;
    private boolean passed;
    private long latencyMs;
    private long inputTokens;
    private long outputTokens;
    private double estimatedCost;
    private String errorClass;
}
