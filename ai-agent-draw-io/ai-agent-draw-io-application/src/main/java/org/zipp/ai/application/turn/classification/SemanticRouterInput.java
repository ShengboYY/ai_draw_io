package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.demand.CurrentInstruction;

/** Semantic Router input after server-owned context assembly and before source planning. */
public record SemanticRouterInput(
        CurrentInstruction instruction,
        RouterContextView context,
        ModelInputBinding modelInputBinding
) {

    public SemanticRouterInput(CurrentInstruction instruction, RouterContextView context) {
        this(instruction, context, ModelInputBinding.unbound());
    }

    public SemanticRouterInput {
        if (instruction == null || context == null || modelInputBinding == null) {
            throw new IllegalArgumentException("semantic router input values must not be null");
        }
    }

    public SemanticRouterInput withModelInputBinding(ModelInputBinding binding) {
        return new SemanticRouterInput(instruction, context, binding);
    }

    /** Digest of the complete router projection, independent of any runtime ADK session. */
    public String inputDigest() {
        return ModelInputBinding.digestOf(instruction.digest(), context.toString());
    }
}
