package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.CurrentInstruction;

/** Semantic Router input after server-owned context assembly and before source planning. */
public record SemanticRouterInput(CurrentInstruction instruction, RouterContextView context) {

    public SemanticRouterInput {
        if (instruction == null || context == null) {
            throw new IllegalArgumentException("semantic router input values must not be null");
        }
    }
}
