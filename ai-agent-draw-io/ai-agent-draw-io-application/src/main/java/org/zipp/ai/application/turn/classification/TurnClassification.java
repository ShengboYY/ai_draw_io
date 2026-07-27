package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.SourceDemandProposal;
import org.zipp.ai.application.turn.demand.SourceDemandResolution;

/** Immutable aggregation of the two independent model proposals and deterministic resolution. */
public record TurnClassification(
        CurrentInstruction instruction,
        SemanticIntent intent,
        SourceDemandProposal demandProposal,
        SourceDemandResolution demandResolution
) {

    public TurnClassification {
        if (instruction == null || intent == null || demandProposal == null || demandResolution == null) {
            throw new IllegalArgumentException("classification values must not be null");
        }
    }
}
