package org.zipp.ai.application.turn.demand;

// Model output is intentionally untrusted until deterministic resolution succeeds.

/** Untrusted proposal returned by a restricted-input interpreter. */
public sealed interface SourceDemandProposal
        permits NoSourceDemandProposal, TypedSourceDemandProposal, AmbiguousSourceDemandProposal {
}
