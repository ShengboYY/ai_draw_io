package org.zipp.ai.application.turn.classification;

public sealed interface SemanticIntentOutcome
        permits SemanticIntentReady, SemanticIntentUnavailable {
}
