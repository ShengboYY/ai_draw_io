package org.zipp.ai.application.turn.demand;

// Policy versions are pinned with the turn decision rather than read from live flags.

/** Versioned deterministic source policy for the unified semantic decision. */
public record DemandResolutionPolicy(
        String modelVersion,
        String policyVersion,
        boolean plainFallbackSigned
) {

    public DemandResolutionPolicy {
        if (modelVersion == null || modelVersion.isBlank()
                || policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("invalid demand resolution policy");
        }
    }

    public static DemandResolutionPolicy m2Default() {
        return new DemandResolutionPolicy(
                "v2-semantic-router",
                "v3-source-policy",
                true);
    }
}
