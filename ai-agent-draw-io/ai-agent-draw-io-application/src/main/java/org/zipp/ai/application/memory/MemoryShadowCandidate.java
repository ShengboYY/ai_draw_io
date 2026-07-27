package org.zipp.ai.application.memory;

/**
 * Bounded output from a future automatic extractor. The reference is opaque and the candidate is
 * never accepted as a user confirmation or persisted as a v1 Memory candidate.
 */
public record MemoryShadowCandidate(
        String candidateRef,
        String decisionKey,
        String applicabilityStage,
        String canonicalText
) {
    public MemoryShadowCandidate {
        required(candidateRef, "candidateRef");
        required(decisionKey, "decisionKey");
        required(applicabilityStage, "applicabilityStage");
        if (canonicalText == null) {
            throw new IllegalArgumentException("canonicalText must not be null");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
