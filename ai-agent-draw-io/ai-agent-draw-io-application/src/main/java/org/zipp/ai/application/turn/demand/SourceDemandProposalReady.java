package org.zipp.ai.application.turn.demand;

// A ready proposal is not yet an accepted source demand.

public record SourceDemandProposalReady(SourceDemandProposal proposal)
        implements SourceDemandProposalOutcome {

    public SourceDemandProposalReady {
        if (proposal == null) {
            throw new IllegalArgumentException("proposal must not be null");
        }
    }
}
