package org.zipp.ai.application.memory;

import java.util.List;

/** Memory repository boundary; it deliberately has no Profile or source repository dependency. */
public interface MemoryCandidateStorePort {
    MemoryProposalOutcome propose(SanitizedMemoryProposal proposal);

    /** Lists only still-pending candidates owned by the requested Chartbook. */
    List<MemoryCandidateProposal> listPending(String ownerKey, String chartbookId);

    MemoryMaterializeOutcome materialize(MemoryMaterializeCommand command);

    MemoryMaterializeOutcome revoke(MemoryCandidateFence fence);

    /** Deletes a pending candidate as a scrubbed tombstone; it cannot be materialized afterward. */
    MemoryMaterializeOutcome delete(MemoryCandidateFence fence);

    List<ConfirmedMemory> recall(String ownerKey, String chartbookId, int limit);
}
