package org.zipp.ai.application.turn.planning;

/** One indivisible authorized Direct candidate fact returned by Probe. */
public record DirectCandidateFact(
        SourceProbeBinding binding,
        String candidateRef,
        DirectCandidateOrigin origin,
        String observationFingerprint,
        String clarificationRef
) {

    public DirectCandidateFact {
        if (binding == null || origin == null
                || candidateRef == null || candidateRef.isBlank()
                || observationFingerprint == null || observationFingerprint.isBlank()
                || clarificationRef == null || clarificationRef.isBlank()) {
            throw new IllegalArgumentException("Direct candidate fact values must not be blank");
        }
    }
}
