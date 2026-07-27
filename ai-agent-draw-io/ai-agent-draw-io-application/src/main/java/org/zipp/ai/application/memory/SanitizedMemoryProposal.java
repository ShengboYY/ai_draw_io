package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.time.Duration;

/** The only payload shape accepted by the persistence port. */
public record SanitizedMemoryProposal(
        TurnKey turn,
        String chartbookId,
        String diagramId,
        String candidateId,
        String declarationDigest,
        String decisionKey,
        String applicabilityStage,
        String scope,
        String canonicalText,
        String policyVersion,
        Duration ttl
) {

    public SanitizedMemoryProposal {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        required(chartbookId, "chartbookId");
        required(diagramId, "diagramId");
        required(candidateId, "candidateId");
        required(declarationDigest, "declarationDigest");
        required(decisionKey, "decisionKey");
        required(applicabilityStage, "applicabilityStage");
        required(scope, "scope");
        required(canonicalText, "canonicalText");
        required(policyVersion, "policyVersion");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
