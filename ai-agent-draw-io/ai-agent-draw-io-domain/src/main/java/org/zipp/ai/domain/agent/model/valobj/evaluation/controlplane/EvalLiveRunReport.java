package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalStatisticalReport;

/** Safe report projection persisted for Mode C and Release runs. */
@Value
@Builder
public class EvalLiveRunReport {
    EvalStatisticalReport statistics;
    EvalStatisticalReport.Comparison comparison;
    EvalLiveRunReadiness readiness;
    EvalGateDecisionRecord gate;
}
