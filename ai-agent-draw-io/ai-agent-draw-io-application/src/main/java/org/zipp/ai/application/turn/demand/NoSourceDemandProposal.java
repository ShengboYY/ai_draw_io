package org.zipp.ai.application.turn.demand;

// No-source proposals still need current-instruction evidence before acceptance.

public record NoSourceDemandProposal(
        ProposalEvidence evidence,
        String safeReason
) implements SourceDemandProposal {

    public NoSourceDemandProposal {
        if (evidence == null || safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("no-source proposal values must not be blank");
        }
    }
}
