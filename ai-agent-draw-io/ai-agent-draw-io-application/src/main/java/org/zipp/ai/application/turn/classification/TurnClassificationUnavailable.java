package org.zipp.ai.application.turn.classification;

public record TurnClassificationUnavailable(String code) implements TurnClassificationOutcome {

    public TurnClassificationUnavailable {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
