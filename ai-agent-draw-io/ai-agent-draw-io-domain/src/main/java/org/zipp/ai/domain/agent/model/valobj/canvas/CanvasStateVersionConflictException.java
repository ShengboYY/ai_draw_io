package org.zipp.ai.domain.agent.model.valobj.canvas;

public class CanvasStateVersionConflictException extends RuntimeException {

    public CanvasStateVersionConflictException(String userId, String diagramId, Long expectedVersion) {
        super("Canvas state version conflict. userId=" + userId
                + " diagramId=" + diagramId
                + " expectedVersion=" + expectedVersion);
    }
}
