package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.time.Instant;

/** Policy-approved observation; this is the only shape accepted by the Auto Memory store. */
public record SanitizedAutoMemoryObservation(
        AutoMemoryScope scope,
        AutoMemoryType type,
        String semanticKey,
        String title,
        String canonicalText,
        TurnKey sourceTurn,
        String sourceDiagramId,
        MemoryObservationKind observationKind,
        double confidence,
        String policyVersion,
        String observationDigest,
        Instant observedAt
) {
    public SanitizedAutoMemoryObservation {
        if (scope == null || type == null || sourceTurn == null || observationKind == null
                || observedAt == null) {
            throw new IllegalArgumentException("sanitized observation identity must not be null");
        }
        required(semanticKey, "semanticKey");
        required(title, "title");
        required(canonicalText, "canonicalText");
        required(policyVersion, "policyVersion");
        required(observationDigest, "observationDigest");
    }

    public boolean explicit() {
        return observationKind.isExplicit();
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
