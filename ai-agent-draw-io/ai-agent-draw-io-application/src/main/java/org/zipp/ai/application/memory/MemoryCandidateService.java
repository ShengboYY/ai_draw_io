package org.zipp.ai.application.memory;

import java.util.List;
import java.util.Objects;

/** User-facing candidate confirmation boundary; no HTTP adapter may bypass the owner fence. */
public final class MemoryCandidateService {
    private final MemoryCandidateStorePort store;

    public MemoryCandidateService(MemoryCandidateStorePort store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<MemoryCandidateProposal> listPending(String ownerKey, String chartbookId) {
        return store.listPending(ownerKey, chartbookId);
    }

    public MemoryMaterializeOutcome confirm(MemoryCandidateFence fence) {
        return store.materialize(new MemoryMaterializeCommand(fence));
    }

    public MemoryMaterializeOutcome revoke(MemoryCandidateFence fence) {
        return store.revoke(fence);
    }
}
