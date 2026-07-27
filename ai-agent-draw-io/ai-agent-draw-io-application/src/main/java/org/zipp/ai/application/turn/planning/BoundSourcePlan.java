package org.zipp.ai.application.turn.planning;

import java.util.Objects;

/** Final planner authority consumed unchanged by later source-aware stages. */
public final class BoundSourcePlan {

    private final SourceAwareDrawPlan plan;
    private final SourcePlanIdentity identity;
    private final SourceExecutionEntry entry;

    BoundSourcePlan(
            SourceAwareDrawPlan plan,
            SourcePlanIdentity identity,
            SourceExecutionEntry entry
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.entry = Objects.requireNonNull(entry, "entry");
        validateEntry();
    }

    public SourceAwareDrawPlan plan() {
        return plan;
    }

    public SourcePlanIdentity identity() {
        return identity;
    }

    public SourceExecutionEntry entry() {
        return entry;
    }

    private void validateEntry() {
        if (entry instanceof SourceExecutionEntry.SignedDirectOnly directOnly) {
            if (!(plan instanceof SourceAwareDrawPlan.OptionalComposite optional)
                    || !optional.validatedDirectOnlyFallback().branchId()
                    .equals(directOnly.branchId())) {
                throw new IllegalArgumentException("SIGNED_DIRECT_ONLY_ENTRY_MISMATCH");
            }
        }
    }
}
