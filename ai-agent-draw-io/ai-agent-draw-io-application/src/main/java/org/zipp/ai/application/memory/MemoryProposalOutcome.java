package org.zipp.ai.application.memory;

public sealed interface MemoryProposalOutcome
        permits MemoryProposalOutcome.Accepted, MemoryProposalOutcome.AlreadyExists,
        MemoryProposalOutcome.Rejected {
    record Accepted(MemoryCandidateProposal proposal) implements MemoryProposalOutcome {
    }

    record AlreadyExists(MemoryCandidateProposal proposal) implements MemoryProposalOutcome {
    }

    record Rejected(String code) implements MemoryProposalOutcome {
    }
}
