package org.zipp.ai.application.turn.agent;

/** Read-only structural facts; visual quality is evaluated by the delegated reviewer agent. */
public record DiagramDraftStructure(
        int nodeCount,
        int edgeCount,
        int cellCount
) {

    public DiagramDraftStructure {
        if (nodeCount < 0 || edgeCount < 0 || cellCount < 0
                || nodeCount + edgeCount > cellCount) {
            throw new IllegalArgumentException("diagram structure counts are invalid");
        }
    }
}
