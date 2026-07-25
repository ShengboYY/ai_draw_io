package org.zipp.ai.application.turn.classification;

public record SemanticIntentUnavailable(String code) implements SemanticIntentOutcome {

    public SemanticIntentUnavailable {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
