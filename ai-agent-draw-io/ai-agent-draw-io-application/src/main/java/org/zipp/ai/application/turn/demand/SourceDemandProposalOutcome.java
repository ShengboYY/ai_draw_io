package org.zipp.ai.application.turn.demand;

// Application-owned outcome; transport and source adapters stay outside this contract.

public sealed interface SourceDemandProposalOutcome
        permits SourceDemandProposalReady, SourceDemandInterpreterUnavailable {
}
