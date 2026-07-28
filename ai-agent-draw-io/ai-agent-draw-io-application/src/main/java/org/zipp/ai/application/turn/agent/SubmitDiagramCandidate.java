package org.zipp.ai.application.turn.agent;

/** Explicit success action; ordinary prose can never terminate a run. */
public record SubmitDiagramCandidate(
        DraftRef draftRef,
        String expectedDigest,
        String assistantMessage
) implements DiagramAgentAction {

    public SubmitDiagramCandidate {
        expectedDigest = expectedDigest == null ? "" : expectedDigest.trim();
        assistantMessage = assistantMessage == null ? "" : assistantMessage.trim();
        if (draftRef == null || expectedDigest.isBlank()
                || assistantMessage.isBlank() || assistantMessage.length() > 16_000) {
            throw new IllegalArgumentException("submitted candidate values are invalid");
        }
    }
}
