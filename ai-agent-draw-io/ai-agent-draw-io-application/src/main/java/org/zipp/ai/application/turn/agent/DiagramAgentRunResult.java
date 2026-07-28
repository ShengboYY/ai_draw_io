package org.zipp.ai.application.turn.agent;

/** Submitted working copy captured before the attempt workspace is discarded. */
public record DiagramAgentRunResult(
        DraftRef draftRef,
        String draftDigest,
        String canvasXml,
        String assistantMessage,
        int stepCount,
        int mutationCount
) {

    public DiagramAgentRunResult {
        if (draftRef == null || draftDigest == null || draftDigest.isBlank()
                || canvasXml == null || canvasXml.isBlank()
                || assistantMessage == null || assistantMessage.isBlank()
                || stepCount <= 0 || mutationCount < 0) {
            throw new IllegalArgumentException("diagram agent run result is invalid");
        }
    }
}
