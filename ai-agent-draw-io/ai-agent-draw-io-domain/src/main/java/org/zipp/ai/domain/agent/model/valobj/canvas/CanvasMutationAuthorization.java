package org.zipp.ai.domain.agent.model.valobj.canvas;

import java.util.Set;

public record CanvasMutationAuthorization(
        Set<String> allowedCellIds,
        Set<CanvasField> allowedFields,
        CanvasRepairScope scope
) {
    public CanvasMutationAuthorization {
        allowedCellIds = allowedCellIds == null ? Set.of() : Set.copyOf(allowedCellIds);
        allowedFields = allowedFields == null ? Set.of() : Set.copyOf(allowedFields);
        scope = scope == null ? CanvasRepairScope.TARGET_CELLS : scope;
    }

    public static CanvasMutationAuthorization unrestricted() {
        return new CanvasMutationAuthorization(Set.of(), Set.of(), CanvasRepairScope.WHOLE_CANVAS);
    }
}
