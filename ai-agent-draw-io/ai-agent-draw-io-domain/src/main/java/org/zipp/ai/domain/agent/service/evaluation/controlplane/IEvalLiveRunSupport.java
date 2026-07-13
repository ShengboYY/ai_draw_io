package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalLiveRunReadiness;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;

/** Port for provider execution and approved operational readiness used by live Eval Runs. */
public interface IEvalLiveRunSupport {
    LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef);
    /** Target-aware seam; legacy test/support implementations remain source compatible. */
    default LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef, EvaluationTarget target) {
        return executionFactory(candidateRef);
    }
    IEvalJudge judge();
    EvalLiveRunReadiness readiness();
    default IDiagramImageRenderer diagramRenderer() { return null; }
}
