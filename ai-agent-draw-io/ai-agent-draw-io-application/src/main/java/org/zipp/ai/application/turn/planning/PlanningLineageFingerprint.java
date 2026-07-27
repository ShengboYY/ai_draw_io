package org.zipp.ai.application.turn.planning;

/** Immutable identity shared by Pre-Planner output and every later source-aware stage. */
public record PlanningLineageFingerprint(String value) {

    public PlanningLineageFingerprint {
        PlanningContractValues.digest(value, "planning lineage fingerprint");
    }
}
