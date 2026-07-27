package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.MemoryWriteDeclaration;
import org.zipp.ai.application.turn.TurnKey;

/** Explicit API command; model output cannot create this command implicitly. */
public record MemoryProposalCommand(
        TurnKey turn,
        String chartbookId,
        String diagramId,
        String candidateId,
        String declarationDigest,
        MemoryWriteDeclaration declaration,
        String decisionKey,
        String applicabilityStage,
        String canonicalText
) {

    public MemoryProposalCommand {
        if (turn == null || declaration == null) {
            throw new IllegalArgumentException("turn and declaration must not be null");
        }
        required(chartbookId, "chartbookId");
        required(diagramId, "diagramId");
        required(candidateId, "candidateId");
        required(declarationDigest, "declarationDigest");
        required(decisionKey, "decisionKey");
        required(applicabilityStage, "applicabilityStage");
        required(canonicalText, "canonicalText");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
