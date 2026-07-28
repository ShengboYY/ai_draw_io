package org.zipp.ai.application.turn.agent;

/** Full XML is accepted only for the first CREATE mutation. */
public record CreateDraftRequest(String canvasXml) implements DiagramAgentToolRequest {

    public CreateDraftRequest {
        canvasXml = canvasXml == null ? "" : canvasXml.trim();
        if (canvasXml.isBlank()) {
            throw new IllegalArgumentException("create draft XML is required");
        }
    }

    @Override
    public String toolName() {
        return "create_draft";
    }
}
