package org.zipp.ai.application.memory;

import java.util.Objects;

/** Application use case that makes sanitization a mandatory predecessor of persistence. */
public final class MemoryProposalService {
    private final MemoryPolicySanitizer sanitizer;
    private final MemoryCandidateStorePort store;

    public MemoryProposalService(MemoryCandidateStorePort store) {
        this(new MemoryPolicySanitizer(), store);
    }

    public MemoryProposalService(MemoryPolicySanitizer sanitizer, MemoryCandidateStorePort store) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.store = Objects.requireNonNull(store, "store");
    }

    public MemoryProposalOutcome propose(MemoryProposalCommand command) {
        MemoryPolicySanitizer.SanitizationOutcome outcome = sanitizer.sanitize(command);
        if (outcome instanceof MemoryPolicySanitizer.SanitizationOutcome.Rejected rejected) {
            return new MemoryProposalOutcome.Rejected(rejected.code());
        }
        SanitizedMemoryProposal proposal =
                ((MemoryPolicySanitizer.SanitizationOutcome.Accepted) outcome).proposal();
        return store.propose(proposal);
    }
}
