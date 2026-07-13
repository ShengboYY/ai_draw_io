package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

/**
 * Resolved Run manifest snapshot; historical execution must not re-read the current Profile preset.
 *
 * <p>This R0 contract does not sanitize arbitrary JSON. R3 must create instances through the Profile
 * resolver after canonicalization and credential redaction; callers must not persist directly
 * constructed instances before that boundary exists.</p>
 */
public record EvaluationProfileSnapshot(
        String profileId,
        String profileVersion,
        EvaluationTarget target,
        String runnerAdapter,
        EvalRunMode mode,
        int repetitions,
        boolean gateEligible,
        String canonicalConfigJson,
        String configHash,
        boolean customized) {
}
