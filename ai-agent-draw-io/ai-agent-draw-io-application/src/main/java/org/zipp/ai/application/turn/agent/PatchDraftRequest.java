package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Digest-bound batch of local cell mutations. */
public record PatchDraftRequest(
        DraftRef draftRef,
        String expectedDigest,
        List<DraftCellMutation> mutations
) implements DiagramAgentToolRequest {

    public PatchDraftRequest {
        if (draftRef == null || expectedDigest == null || expectedDigest.isBlank()) {
            throw new IllegalArgumentException("patch draft ref and digest are required");
        }
        expectedDigest = expectedDigest.trim();
        mutations = List.copyOf(mutations == null ? List.of() : mutations);
        if (mutations.isEmpty()) {
            throw new IllegalArgumentException("patch mutations are required");
        }
    }

    @Override
    public String toolName() {
        return "patch_draft";
    }
}
