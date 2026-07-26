package org.zipp.ai.application.turn.demand;

import java.util.List;
import java.util.Optional;

/** Evidence and confidence attached to a model proposal, without source content. */
public record ProposalEvidence(
        List<CurrentInstructionSpan> spans,
        Confidence confidence,
        Optional<String> relevanceQuery,
        String inputDigest,
        String modelVersion,
        String policyVersion
) {

    public ProposalEvidence {
        spans = List.copyOf(spans == null ? List.of() : spans);
        if (confidence == null || relevanceQuery == null
                || inputDigest == null || inputDigest.isBlank()
                || modelVersion == null || modelVersion.isBlank()
                || policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("evidence values must not be null");
        }
        relevanceQuery = relevanceQuery.map(String::trim);
    }
}
