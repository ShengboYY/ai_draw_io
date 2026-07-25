package org.zipp.ai.application.turn.demand;

public record SourceDemandInterpreterUnavailable(String code)
        implements SourceDemandProposalOutcome {

    public SourceDemandInterpreterUnavailable {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
