package org.zipp.ai.application.turn.planning;

import java.util.Objects;

/** Authorizes a post-Probe Direct-only transition only from the root plan's signed branch. */
public final class OptionalCompositeFallbackPlanner {

    public SourcePlanDecision.DirectOnlyReady authorize(
            SourcePlanDecision.SourceReady primary,
            OptionalEvidenceOutcome.FallbackEligible fallback
    ) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(fallback, "fallback");
        if (!(primary.bound().plan() instanceof SourceAwareDrawPlan.OptionalComposite optional)) {
            throw new IllegalArgumentException("OPTIONAL_COMPOSITE_PLAN_REQUIRED");
        }
        BoundSourcePlan directOnly = new BoundSourcePlan(
                optional,
                primary.bound().identity(),
                new SourceExecutionEntry.SignedDirectOnly(
                        optional.validatedDirectOnlyFallback().branchId(),
                        fallback.reason()));
        return new SourcePlanDecision.DirectOnlyReady(directOnly);
    }
}
