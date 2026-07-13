package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalLiveRunReadiness;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

/** Port for provider execution and approved operational readiness used by live Eval Runs. */
public interface IEvalLiveRunSupport {
    LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef);
    IEvalJudge judge();
    EvalLiveRunReadiness readiness();
}
