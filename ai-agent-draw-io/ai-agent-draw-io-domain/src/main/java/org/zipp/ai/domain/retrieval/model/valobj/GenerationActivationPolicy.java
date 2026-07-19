package org.zipp.ai.domain.retrieval.model.valobj;

/** Versioned release thresholds; the fingerprint pins the exact policy used by the shadow report. */
public record GenerationActivationPolicy(String policyFingerprint, int minimumSamples,
                                         double minimumRecallDelta, double minimumNdcgDelta,
                                         double maximumP95LatencyRatio) {
    public GenerationActivationPolicy {
        if (policyFingerprint == null || !policyFingerprint.matches("[0-9a-f]{64}")
                || minimumSamples < 1 || !Double.isFinite(minimumRecallDelta)
                || !Double.isFinite(minimumNdcgDelta)
                || !Double.isFinite(maximumP95LatencyRatio) || maximumP95LatencyRatio <= 0) {
            throw new IllegalArgumentException("generation activation policy is invalid");
        }
    }
}
