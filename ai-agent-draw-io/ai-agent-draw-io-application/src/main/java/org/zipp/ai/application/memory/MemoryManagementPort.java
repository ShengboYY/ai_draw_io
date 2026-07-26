package org.zipp.ai.application.memory;

import java.util.List;

/** User view/edit/disable/delete boundary for confirmed Memory. */
public interface MemoryManagementPort {
    List<ConfirmedMemory> list(String ownerKey, String chartbookId, boolean includeDisabled);

    MemoryManagementOutcome edit(ConfirmedMemoryEditCommand command);

    MemoryManagementOutcome disable(ConfirmedMemoryFence fence);

    MemoryManagementOutcome delete(ConfirmedMemoryFence fence);
}
