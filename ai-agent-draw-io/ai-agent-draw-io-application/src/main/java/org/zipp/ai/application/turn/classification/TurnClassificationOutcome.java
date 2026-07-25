package org.zipp.ai.application.turn.classification;

public sealed interface TurnClassificationOutcome
        permits TurnClassificationReady, TurnClassificationUnavailable {
}
