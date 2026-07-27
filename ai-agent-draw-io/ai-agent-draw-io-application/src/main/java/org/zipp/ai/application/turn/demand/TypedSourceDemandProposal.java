package org.zipp.ai.application.turn.demand;

// Referents remain opaque declarations until a separately fenced authorization stage.

import java.util.List;

public record TypedSourceDemandProposal(
        SourceDemandKind kind,
        List<String> attachmentRefs,
        String relevanceQuery,
        ProposalEvidence evidence,
        String safeReason
) implements SourceDemandProposal {

    public TypedSourceDemandProposal {
        if (kind == null || evidence == null || safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("typed source proposal values must not be blank");
        }
        attachmentRefs = List.copyOf(attachmentRefs == null ? List.of() : attachmentRefs);
        relevanceQuery = relevanceQuery == null ? null : relevanceQuery.trim();
    }
}
