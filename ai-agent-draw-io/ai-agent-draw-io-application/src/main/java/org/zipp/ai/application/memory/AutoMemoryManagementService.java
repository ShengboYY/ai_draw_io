package org.zipp.ai.application.memory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** User-facing management use case; edits pass through the same content policy as extraction. */
public final class AutoMemoryManagementService {
    private final AutoMemoryManagementStorePort store;
    private final MemoryPolicySanitizer sanitizer;
    private final Clock clock;

    public AutoMemoryManagementService(AutoMemoryManagementStorePort store) {
        this(store, new MemoryPolicySanitizer(), Clock.systemUTC());
    }

    public AutoMemoryManagementService(
            AutoMemoryManagementStorePort store,
            MemoryPolicySanitizer sanitizer,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<AutoMemory> list(
            AutoMemoryScope scope,
            boolean includeObserved,
            boolean includeDisabled
    ) {
        return store.list(scope, includeObserved, includeDisabled);
    }

    public AutoMemoryManagementOutcome edit(AutoMemoryFence fence, String canonicalText) {
        MemoryPolicySanitizer.TextSanitizationOutcome sanitized =
                sanitizer.sanitizeText(canonicalText);
        if (sanitized instanceof MemoryPolicySanitizer.TextSanitizationOutcome.Rejected rejected) {
            return new AutoMemoryManagementOutcome.Rejected(rejected.code());
        }
        String safeText =
                ((MemoryPolicySanitizer.TextSanitizationOutcome.Accepted) sanitized).text();
        return store.edit(
                fence, safeText, AutoMemoryObservationService.POLICY_VERSION, clock.instant());
    }

    public AutoMemoryManagementOutcome disable(AutoMemoryFence fence) {
        return store.disable(fence, clock.instant());
    }

    public AutoMemoryManagementOutcome activate(AutoMemoryFence fence) {
        return store.activate(fence, clock.instant());
    }

    public AutoMemoryManagementOutcome delete(AutoMemoryFence fence) {
        return store.delete(fence);
    }
}
