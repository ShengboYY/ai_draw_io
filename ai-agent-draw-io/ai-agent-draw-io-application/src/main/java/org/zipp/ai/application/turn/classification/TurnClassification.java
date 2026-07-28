package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.SourceDemandProposal;
import org.zipp.ai.application.turn.demand.SourceDemandResolution;
import org.zipp.ai.application.turn.skill.ResolvedDiagramSkillSelection;

/** Immutable aggregation of the two independent model proposals and deterministic resolution. */
public record TurnClassification(
        CurrentInstruction instruction,
        SemanticIntent intent,
        SourceDemandProposal demandProposal,
        SourceDemandResolution demandResolution,
        ResolvedDiagramSkillSelection skillSelection
) {

    public TurnClassification {
        if (instruction == null || intent == null || demandProposal == null
                || demandResolution == null || skillSelection == null) {
            throw new IllegalArgumentException("classification values must not be null");
        }
    }

    /** Compatibility constructor for source-planning fixtures that predate V2 skills. */
    public TurnClassification(
            CurrentInstruction instruction,
            SemanticIntent intent,
            SourceDemandProposal demandProposal,
            SourceDemandResolution demandResolution
    ) {
        this(instruction, intent, demandProposal, demandResolution,
                ResolvedDiagramSkillSelection.empty());
    }
}
