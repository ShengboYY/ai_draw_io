package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.TurnKey;

/** Immutable values that every Probe outcome must echo. */
public record SourceProbeBinding(
        TurnKey turn,
        PlanningLineageFingerprint lineage,
        String declarationDigest,
        String contextReadSetDigest,
        String inputBindingDigest
) {

    public SourceProbeBinding {
        if (turn == null || lineage == null) {
            throw new IllegalArgumentException("probe turn and lineage must not be null");
        }
        PlanningContractValues.digest(declarationDigest, "declarationDigest");
        PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
        PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
    }
}
