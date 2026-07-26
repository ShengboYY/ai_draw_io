package org.zipp.ai.application.turn.planning;

/** Immutable root identity copied through freeze, preparation, and strong commit. */
public record SourcePlanIdentity(
        PlanningLineageFingerprint lineage,
        String planFingerprint
) {

    public SourcePlanIdentity {
        if (lineage == null) {
            throw new IllegalArgumentException("plan lineage must not be null");
        }
        PlanningContractValues.digest(planFingerprint, "planFingerprint");
    }
}
