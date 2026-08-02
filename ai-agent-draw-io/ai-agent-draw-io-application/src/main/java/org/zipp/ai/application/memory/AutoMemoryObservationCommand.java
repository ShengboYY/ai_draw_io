package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

/** Untrusted extractor or explicit-intent output before policy and persistence checks. */
public record AutoMemoryObservationCommand(
        AutoMemoryScope scope,
        AutoMemoryType type,
        String semanticKey,
        String title,
        String canonicalText,
        TurnKey sourceTurn,
        String sourceDiagramId,
        MemoryObservationKind observationKind,
        double confidence
) {
    public AutoMemoryObservationCommand {
        if (scope == null || type == null || sourceTurn == null || observationKind == null) {
            throw new IllegalArgumentException("observation identity must not be null");
        }
        required(semanticKey, "semanticKey");
        required(title, "title");
        if (canonicalText == null) {
            throw new IllegalArgumentException("canonicalText must not be null");
        }
        if (!scope.ownerKey().equals(sourceTurn.ownerKey())) {
            throw new IllegalArgumentException("source turn owner must match Memory owner");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        sourceDiagramId = nullableTrimmed(sourceDiagramId);
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String nullableTrimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
