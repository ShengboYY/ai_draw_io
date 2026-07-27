package org.zipp.ai.domain.agent.model.valobj.visualreview;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;

import java.util.Objects;

/** Server-owned context for one bounded mutation by the dedicated visual repair agent. */
public record VisualRepairContext(
        String diagramType,
        CanvasMutationAuthorization authorization
) {
    public VisualRepairContext {
        diagramType = diagramType == null || diagramType.isBlank() ? "none" : diagramType.trim();
        authorization = Objects.requireNonNull(authorization, "authorization");
    }
}
