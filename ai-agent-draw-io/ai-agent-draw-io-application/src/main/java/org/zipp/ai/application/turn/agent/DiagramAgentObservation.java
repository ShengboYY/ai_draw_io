package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.ModelInputBinding;

import java.util.List;

/** Complete bounded input for one model decision. */
public record DiagramAgentObservation(
        DiagramAgentState state,
        List<String> allowedTools,
        int remainingSteps,
        int remainingMutations,
        int remainingFullXmlInspections
) {

    public DiagramAgentObservation {
        if (state == null || remainingSteps < 0
                || remainingMutations < 0 || remainingFullXmlInspections < 0) {
            throw new IllegalArgumentException("diagram agent observation is invalid");
        }
        allowedTools = List.copyOf(allowedTools == null ? List.of() : allowedTools);
    }

    public ModelInputBinding modelInputBinding() {
        ModelInputBinding base = state.request().modelInputBinding();
        String activeDigest = state.activeDraft() == null ? "" : state.activeDraft().digest();
        String latestOutcome = state.latestToolResult() == null
                ? ""
                : state.latestToolResult().toolName() + ":" + state.latestToolResult().outcomeCode();
        String history = state.steps().stream()
                .map(step -> step.stepNumber() + ":" + step.actionType() + ":" + step.toolName()
                        + ":" + step.outcomeCode() + ":" + step.afterDraftDigest())
                .toList()
                .toString();
        return ModelInputBinding.bound(
                base.turnKey(),
                base.contextReadSetDigest(),
                ModelInputBinding.digestOf(
                        base.inputDigest(),
                        state.skills().selectionBindingDigest(),
                        activeDigest,
                        latestOutcome,
                        history,
                        Integer.toString(state.stepCount())));
    }
}
