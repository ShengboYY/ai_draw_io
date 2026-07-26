package org.zipp.ai.application.turn.planning;

import java.util.Objects;

/** Planner-issued selector preserving the complete origin-specific Direct fact. */
public final class DirectSelector {

    private final DirectCandidateFact candidate;

    DirectSelector(DirectCandidateFact candidate) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
    }

    public DirectCandidateFact candidate() {
        return candidate;
    }
}
