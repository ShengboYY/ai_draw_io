package org.zipp.ai.application.turn.checkpoint;

import java.util.Objects;

/** Bounded canonical payload produced by the domain-version route codec. */
public record EncodedTurnRouteDecision(String kind, String json) {

    public EncodedTurnRouteDecision {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(json, "json");
        if (kind.isBlank() || kind.length() > 64
                || json.isBlank() || json.length() > 128_000
                || json.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("encoded route decision must be bounded");
        }
    }
}
