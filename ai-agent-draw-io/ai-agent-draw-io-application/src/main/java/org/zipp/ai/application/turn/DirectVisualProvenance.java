package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;

/** Opaque visual provenance and retention identity; this is deliberately not a citation. */
public record DirectVisualProvenance(
        String provenanceRef,
        String sourceIdentityRef,
        DirectCandidateOrigin origin,
        String observationFingerprint
) {

    public DirectVisualProvenance {
        ContractValues.requiredText(provenanceRef, "provenanceRef");
        ContractValues.requiredText(sourceIdentityRef, "sourceIdentityRef");
        ContractValues.requiredText(observationFingerprint, "observationFingerprint");
        if (origin == null) {
            throw new IllegalArgumentException("origin must not be null");
        }
    }
}
