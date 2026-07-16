package org.zipp.ai.domain.agent.model.valobj.visualreview;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;

import java.util.Objects;

/** Server-owned context for continuing the existing Drawer loop after visual review. */
public record DrawerContinuationContext(
        String diagramType,
        CanvasMutationAuthorization authorization
) {
    public DrawerContinuationContext {
        diagramType = diagramType == null || diagramType.isBlank() ? "none" : diagramType.trim();
        authorization = Objects.requireNonNull(authorization, "authorization");
    }
}
