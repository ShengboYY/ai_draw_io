package org.zipp.ai.domain.agent.model.valobj.canvas;

import org.zipp.ai.types.util.SecretLogSanitizer;

public class CanvasStateVersionConflictException extends RuntimeException {

    public CanvasStateVersionConflictException(String userId, String diagramId, Long expectedVersion) {
        super("Canvas state version conflict. userId=" + SecretLogSanitizer.maskCapability(userId)
                + " diagramId=" + diagramId
                + " expectedVersion=" + expectedVersion);
    }
}
