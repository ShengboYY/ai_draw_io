package org.zipp.ai.application.turn.skill;

/** Bounded skill guidance loaded for one Plain agent attempt. */
public record LoadedDiagramSkill(
        String name,
        String diagramType,
        String contentDigest,
        String body,
        boolean truncated
) {

    public LoadedDiagramSkill {
        if (name == null || name.isBlank()
                || diagramType == null || diagramType.isBlank()
                || contentDigest == null || contentDigest.length() != 64
                || body == null || body.isBlank()) {
            throw new IllegalArgumentException("loaded diagram skill values are invalid");
        }
        name = name.trim();
        diagramType = diagramType.trim();
        body = body.trim();
    }
}
