package org.zipp.ai.application.turn.classification;

public record SemanticIntentReady(SemanticIntent intent) implements SemanticIntentOutcome {

    public SemanticIntentReady {
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
    }
}
