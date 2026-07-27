package org.zipp.ai.application.turn;

import java.util.Optional;

/** One grounded Canvas/citations/message/terminal transaction. */
public record GroundedTurnCommit(
        FencedAttempt attempt,
        SourceCommitBinding sourceBinding,
        String diagramId,
        long expectedCanvasVersion,
        String expectedCanvasContextDigest,
        String canvasXml,
        String assistantMessage,
        String payloadRef,
        ValidatedCitationManifest citations,
        Optional<DirectVisualProvenance> directProvenance
) {

    public GroundedTurnCommit {
        if (attempt == null || sourceBinding == null || citations == null) {
            throw new IllegalArgumentException("Grounded commit bindings must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        DirectTurnCommit.canvasPin(expectedCanvasVersion, expectedCanvasContextDigest);
        ContractValues.requiredText(canvasXml, "canvasXml");
        ContractValues.requiredText(assistantMessage, "assistantMessage");
        ContractValues.requiredText(payloadRef, "payloadRef");
        directProvenance = directProvenance == null ? Optional.empty() : directProvenance;
    }
}
