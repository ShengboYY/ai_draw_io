package org.zipp.ai.domain.multimodal;

import java.util.Objects;

/** A bounded visual fact anchored to an already-authorized Evidence Unit. */
public record VerifiedObservation(String evidenceId, ObservationKind kind, String text,
                                  ObservationBounds bounds, String direction, double confidence) {
    public VerifiedObservation {
        evidenceId = required(evidenceId, "evidenceId");
        kind = Objects.requireNonNull(kind, "kind");
        text = required(text, "text");
        if (text.length() > 1_000) throw new IllegalArgumentException("observation text is too long");
        bounds = Objects.requireNonNull(bounds, "bounds");
        direction = direction == null ? "" : direction.trim();
        if (direction.length() > 64) throw new IllegalArgumentException("direction is too long");
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
