package org.zipp.ai.application.turn;

/** Closed source-free plan; it contains no source, snapshot, retrieval, or evidence handle. */
public record PlainDrawPlan(PlainDrawAction action, String instruction) {

    public PlainDrawPlan {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        ContractValues.requiredText(instruction, "instruction");
    }
}
