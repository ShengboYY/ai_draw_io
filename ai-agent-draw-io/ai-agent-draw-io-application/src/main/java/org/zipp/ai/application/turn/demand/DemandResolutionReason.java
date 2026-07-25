package org.zipp.ai.application.turn.demand;

// Rule numbers make the deterministic decision auditable without exposing model internals.

public record DemandResolutionReason(int ruleNumber, DemandResolutionCode code) {

    public DemandResolutionReason {
        if (ruleNumber <= 0 || code == null) {
            throw new IllegalArgumentException("invalid demand resolution reason");
        }
    }
}
