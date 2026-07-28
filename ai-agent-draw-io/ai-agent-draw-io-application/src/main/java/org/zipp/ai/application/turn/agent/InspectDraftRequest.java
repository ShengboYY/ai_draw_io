package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Bounded read request against the server-owned current draft. */
public record InspectDraftRequest(
        DraftRef draftRef,
        DraftInspectionScope scope,
        List<String> cellIds,
        String query
) implements DiagramAgentToolRequest {

    public InspectDraftRequest {
        if (draftRef == null || scope == null) {
            throw new IllegalArgumentException("inspect draft ref and scope are required");
        }
        cellIds = List.copyOf(cellIds == null ? List.of() : cellIds);
        query = query == null ? "" : query.trim();
        if (scope == DraftInspectionScope.TARGET_CELLS && cellIds.isEmpty()) {
            throw new IllegalArgumentException("target cell inspection requires cellIds");
        }
        if (scope == DraftInspectionScope.FIND_CELLS && query.isBlank()) {
            throw new IllegalArgumentException("find cell inspection requires query");
        }
    }

    @Override
    public String toolName() {
        return "inspect_draft";
    }
}
