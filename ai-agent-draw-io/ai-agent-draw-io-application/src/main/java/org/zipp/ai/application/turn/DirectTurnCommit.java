package org.zipp.ai.application.turn;

/** One Direct Canvas/provenance/message/terminal transaction. */
public record DirectTurnCommit(
        FencedAttempt attempt,
        SourceCommitBinding sourceBinding,
        String diagramId,
        long expectedCanvasVersion,
        String expectedCanvasContextDigest,
        String canvasXml,
        String assistantMessage,
        String payloadRef,
        DirectVisualProvenance provenance
) {

    public DirectTurnCommit {
        if (attempt == null || sourceBinding == null || provenance == null) {
            throw new IllegalArgumentException("Direct commit bindings must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        canvasPin(expectedCanvasVersion, expectedCanvasContextDigest);
        ContractValues.requiredText(canvasXml, "canvasXml");
        ContractValues.requiredText(assistantMessage, "assistantMessage");
        ContractValues.requiredText(payloadRef, "payloadRef");
    }

    static void canvasPin(long version, String digest) {
        if (version < 0) {
            throw new IllegalArgumentException("expectedCanvasVersion must not be negative");
        }
        String normalized = digest == null ? "" : digest.trim();
        if (version == 0 && !normalized.isEmpty()) {
            throw new IllegalArgumentException("absent canvas cannot carry a context digest");
        }
        if (version > 0) {
            SourceCommitBindingDigest.required(normalized, "expectedCanvasContextDigest");
        }
    }
}
