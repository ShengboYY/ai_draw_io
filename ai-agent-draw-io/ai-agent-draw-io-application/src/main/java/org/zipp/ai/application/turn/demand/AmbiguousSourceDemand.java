package org.zipp.ai.application.turn.demand;

// Ambiguity is not a source authorization or a source lookup result.

public record AmbiguousSourceDemand(String reason) implements SourceDemandDecision {

    public AmbiguousSourceDemand {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
