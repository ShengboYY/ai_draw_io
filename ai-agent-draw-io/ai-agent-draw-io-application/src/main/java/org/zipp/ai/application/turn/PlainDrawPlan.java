package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.skill.ResolvedDiagramSkillSelection;

/** Closed source-free plan; it contains only immutable skill identities, never skill bodies. */
public record PlainDrawPlan(
        PlainDrawAction action,
        String instruction,
        String diagramType,
        ResolvedDiagramSkillSelection skillSelection
) {

    public PlainDrawPlan {
        if (action == null || skillSelection == null) {
            throw new IllegalArgumentException("action and skillSelection must not be null");
        }
        ContractValues.requiredText(instruction, "instruction");
        diagramType = diagramType == null ? "unknown" : diagramType.trim();
        if (diagramType.isBlank()) {
            diagramType = "unknown";
        }
    }

    /** Compatibility constructor for plans created before V2 skill binding. */
    public PlainDrawPlan(PlainDrawAction action, String instruction) {
        this(action, instruction, "unknown", ResolvedDiagramSkillSelection.empty());
    }
}
