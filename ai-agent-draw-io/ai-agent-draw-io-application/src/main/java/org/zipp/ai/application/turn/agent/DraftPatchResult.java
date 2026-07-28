package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Result of an atomic patch against one immutable draft version. */
public record DraftPatchResult(
        DiagramDraftSnapshot draft,
        List<String> changedCellIds
) {

    public DraftPatchResult {
        if (draft == null) {
            throw new IllegalArgumentException("patched draft is required");
        }
        changedCellIds = List.copyOf(changedCellIds == null ? List.of() : changedCellIds);
        if (changedCellIds.isEmpty()) {
            throw new IllegalArgumentException("patch must change at least one cell");
        }
    }
}
