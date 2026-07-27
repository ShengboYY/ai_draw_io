package org.zipp.ai.application.turn;

/** Closed source-free response plan; source capabilities cannot be smuggled into the handler. */
public record PlainResponsePlan(
        PlainResponseKind kind,
        String instruction,
        boolean includeCanvasContext
) {

    public PlainResponsePlan {
        if (kind == null) {
            throw new IllegalArgumentException("response kind must not be null");
        }
        ContractValues.requiredText(instruction, "instruction");
    }

    public PlainResponsePlan(PlainResponseKind kind, String instruction) {
        this(kind, instruction, false);
    }
}
