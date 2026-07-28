package org.zipp.ai.application.turn.agent;

/** Fixed code-owned limits; the model cannot increase these values. */
public record DiagramAgentBudget(
        int maxSteps,
        int maxMutations,
        int maxCreateCalls,
        int maxFullXmlInspections,
        int maxRepeatedActions,
        int maxNoProgressSteps
) {

    public DiagramAgentBudget {
        if (maxSteps <= 0 || maxMutations <= 0 || maxCreateCalls <= 0
                || maxFullXmlInspections < 0 || maxRepeatedActions <= 0
                || maxNoProgressSteps <= 0) {
            throw new IllegalArgumentException("diagram agent budget is invalid");
        }
    }

    public static DiagramAgentBudget defaults() {
        return new DiagramAgentBudget(6, 3, 1, 1, 1, 2);
    }
}
