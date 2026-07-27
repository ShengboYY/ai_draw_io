package org.zipp.ai.application.turn.classification;

/** Model boundary for semantic action/target classification; it cannot execute a turn. */
public interface SemanticIntentRouterPort {

    SemanticIntentOutcome route(SemanticRouterInput input);
}
