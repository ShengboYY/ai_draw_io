package org.zipp.ai.application.turn.classification;

public record TurnClassificationReady(TurnClassification classification)
        implements TurnClassificationOutcome {

    public TurnClassificationReady {
        if (classification == null) {
            throw new IllegalArgumentException("classification must not be null");
        }
    }
}
