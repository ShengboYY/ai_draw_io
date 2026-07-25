package org.zipp.ai.application.turn.demand;

// Policy versions are pinned with the turn decision rather than read from live flags.

import java.util.Set;

/** Versioned deterministic acceptance policy for restricted model proposals. */
public record DemandResolutionPolicy(
        String modelVersion,
        String policyVersion,
        Set<Confidence> acceptedNoSourceConfidence,
        Set<Confidence> acceptedRequiredConfidence,
        Set<Confidence> acceptedOptionalConfidence,
        boolean plainFallbackSigned
) {

    public DemandResolutionPolicy {
        if (modelVersion == null || modelVersion.isBlank()
                || policyVersion == null || policyVersion.isBlank()
                || acceptedNoSourceConfidence == null
                || acceptedRequiredConfidence == null
                || acceptedOptionalConfidence == null) {
            throw new IllegalArgumentException("invalid demand resolution policy");
        }
        acceptedNoSourceConfidence = Set.copyOf(acceptedNoSourceConfidence);
        acceptedRequiredConfidence = Set.copyOf(acceptedRequiredConfidence);
        acceptedOptionalConfidence = Set.copyOf(acceptedOptionalConfidence);
    }

    public static DemandResolutionPolicy m2Default() {
        return new DemandResolutionPolicy(
                "m2-demand-model",
                "m2-demand-policy",
                Set.of(Confidence.MEDIUM, Confidence.HIGH),
                Set.of(Confidence.HIGH),
                Set.of(Confidence.MEDIUM, Confidence.HIGH),
                true);
    }
}
