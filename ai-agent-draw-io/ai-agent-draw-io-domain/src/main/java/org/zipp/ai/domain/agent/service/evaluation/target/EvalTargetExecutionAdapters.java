package org.zipp.ai.domain.agent.service.evaluation.target;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneErrorCode;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Small registry that rejects Profile/adapter drift before an Episode is executed. */
public class EvalTargetExecutionAdapters {
    private final Map<EvaluationTarget, EvalTargetExecutionAdapter> adapters = new EnumMap<>(EvaluationTarget.class);

    public EvalTargetExecutionAdapters() {
        this(List.of(new FullAgentEvalAdapter(), new RouterEvalAdapter(), new DrawingEvalAdapter(),
                new VisualReviewEvalAdapter()));
    }

    public EvalTargetExecutionAdapters(List<EvalTargetExecutionAdapter> values) {
        values.forEach(value -> adapters.put(value.target(), value));
    }

    public EvalTargetExecutionAdapter require(EvaluationTarget target, String runnerAdapter) {
        EvalTargetExecutionAdapter adapter = adapters.get(target);
        if (adapter == null || !adapter.runnerAdapter().equals(runnerAdapter)) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Evaluation Profile runnerAdapter does not match Run target");
        }
        return adapter;
    }
}
