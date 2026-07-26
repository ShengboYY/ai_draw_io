package org.zipp.ai.application.turn.planning;

/** Immutable values that every Probe outcome must echo. */
public record SourceProbeBinding(
        PlanningLineageFingerprint lineage,
        String contextReadSetDigest,
        String inputBindingDigest
) {

    public SourceProbeBinding {
        if (lineage == null) {
            throw new IllegalArgumentException("probe lineage must not be null");
        }
        PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
        PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
    }
}
