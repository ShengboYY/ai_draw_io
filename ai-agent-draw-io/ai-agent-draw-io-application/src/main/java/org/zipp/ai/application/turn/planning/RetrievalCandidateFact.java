package org.zipp.ai.application.turn.planning;

/** Owner-fenced opaque Retrieval candidate metadata; it carries no source body or Evidence. */
public record RetrievalCandidateFact(
        SourceProbeBinding binding,
        String candidateRef
) {

    public RetrievalCandidateFact {
        if (binding == null || candidateRef == null || candidateRef.isBlank()) {
            throw new IllegalArgumentException("Retrieval candidate fact values must not be blank");
        }
    }
}
