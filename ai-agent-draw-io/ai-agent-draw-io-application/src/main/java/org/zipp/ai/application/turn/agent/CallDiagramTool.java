package org.zipp.ai.application.turn.agent;

public record CallDiagramTool(DiagramAgentToolRequest request) implements DiagramAgentAction {

    public CallDiagramTool {
        if (request == null) {
            throw new IllegalArgumentException("diagram tool request is required");
        }
    }
}
