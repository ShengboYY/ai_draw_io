package org.zipp.ai.application.turn;

/** One evidence-only answer/claims/citations/message/terminal transaction. */
public record EvidenceAnswerTurnCommit(
        FencedAttempt attempt,
        SourceCommitBinding sourceBinding,
        String diagramId,
        long expectedTargetCanvasVersion,
        String expectedTargetCanvasContextDigest,
        String assistantMessage,
        String payloadRef,
        ValidatedCitationManifest citations
) {

    public EvidenceAnswerTurnCommit {
        if (attempt == null || sourceBinding == null || citations == null) {
            throw new IllegalArgumentException("Evidence answer commit bindings must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        DirectTurnCommit.canvasPin(
                expectedTargetCanvasVersion,
                expectedTargetCanvasContextDigest);
        ContractValues.requiredText(assistantMessage, "assistantMessage");
        ContractValues.requiredText(payloadRef, "payloadRef");
    }
}
