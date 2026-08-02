package org.zipp.ai.application.memory;

import java.time.Instant;
import java.util.List;

/** Persistence boundary for explicit user management of automatic Memory. */
public interface AutoMemoryManagementStorePort {
    List<AutoMemory> list(
            AutoMemoryScope scope,
            boolean includeObserved,
            boolean includeDisabled
    );

    AutoMemoryManagementOutcome edit(
            AutoMemoryFence fence,
            String canonicalText,
            String policyVersion,
            Instant now
    );

    AutoMemoryManagementOutcome disable(AutoMemoryFence fence, Instant now);

    AutoMemoryManagementOutcome activate(AutoMemoryFence fence, Instant now);

    AutoMemoryManagementOutcome delete(AutoMemoryFence fence);
}
