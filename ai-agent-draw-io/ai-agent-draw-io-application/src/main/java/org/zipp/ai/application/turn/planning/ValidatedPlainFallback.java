package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawPlan;

import java.util.Objects;

/**
 * Plain branch signed before Probe. Construction is package-private so only the planner boundary
 * can mint a branch from the complete source-planning requirement.
 */
public final class ValidatedPlainFallback {

    private final String branchId;
    private final PlainDrawPlan plan;

    ValidatedPlainFallback(String branchId, PlainDrawPlan plan) {
        PlanningContractValues.digest(branchId, "fallback branch id");
        this.branchId = branchId;
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    public String branchId() {
        return branchId;
    }

    public PlainDrawPlan plan() {
        return plan;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValidatedPlainFallback value
                && branchId.equals(value.branchId)
                && plan.equals(value.plan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(branchId, plan);
    }
}
