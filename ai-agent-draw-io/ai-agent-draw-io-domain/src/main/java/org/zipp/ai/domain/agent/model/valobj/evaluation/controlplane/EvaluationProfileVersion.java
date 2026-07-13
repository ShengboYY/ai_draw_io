package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

/** Immutable, human-addressable definition of how one Evaluation target is executed and judged. */
public record EvaluationProfileVersion(
        String profileId,
        String version,
        EvaluationTarget target,
        String runnerAdapter,
        EvalRunMode mode,
        int repetitions,
        boolean gateEligible,
        String configJson) {
}
