package org.zipp.ai.application.turn.classification;

/** Untrusted semantic router result; it does not carry source authorization. */
public record SemanticIntent(
        SemanticAction action,
        OutputIntent outputIntent,
        TargetNeed targetNeed,
        String diagramType,
        String skillName
) {

    public SemanticIntent {
        if (action == null || outputIntent == null || targetNeed == null) {
            throw new IllegalArgumentException("semantic intent values must not be null");
        }
        diagramType = diagramType == null ? "unknown" : diagramType;
        skillName = skillName == null ? "none" : skillName;
    }
}
