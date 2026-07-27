package org.zipp.ai.application.turn.planning;

import java.util.Objects;

/** Planner-signed Direct-only branch owned by one Optional Composite root plan. */
public final class ValidatedDirectOnlyFallback {

    private final DirectSelector direct;
    private final String branchId;

    ValidatedDirectOnlyFallback(DirectSelector direct, String branchId) {
        this.direct = Objects.requireNonNull(direct, "direct");
        PlanningContractValues.digest(branchId, "direct-only branch id");
        this.branchId = branchId;
    }

    public DirectSelector direct() {
        return direct;
    }

    public String branchId() {
        return branchId;
    }
}
