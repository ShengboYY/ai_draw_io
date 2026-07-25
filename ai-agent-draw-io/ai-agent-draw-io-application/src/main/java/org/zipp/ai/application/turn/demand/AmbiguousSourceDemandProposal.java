package org.zipp.ai.application.turn.demand;

// The interpreter may request clarification but cannot choose a source.

public record AmbiguousSourceDemandProposal(
        ProposalEvidence evidence,
        String safeReason
) implements SourceDemandProposal {

    public AmbiguousSourceDemandProposal {
        if (evidence == null || safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("ambiguous source proposal values must not be blank");
        }
    }
}
