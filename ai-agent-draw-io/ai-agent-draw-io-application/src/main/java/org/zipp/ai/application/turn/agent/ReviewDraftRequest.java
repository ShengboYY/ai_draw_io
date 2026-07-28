package org.zipp.ai.application.turn.agent;

/** Requests a visual review of one exact attempt-scoped draft version. */
public record ReviewDraftRequest(
        DraftRef draftRef,
        String expectedDigest
) implements DiagramAgentToolRequest {

    public ReviewDraftRequest {
        expectedDigest = expectedDigest == null ? "" : expectedDigest.trim();
        if (draftRef == null || expectedDigest.isBlank()) {
            throw new IllegalArgumentException("review draft identity is required");
        }
    }

    @Override
    public String toolName() {
        return "review_draft";
    }
}
