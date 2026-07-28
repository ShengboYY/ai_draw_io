package org.zipp.ai.application.turn.agent;

/** Compact replay fact; XML and tool arguments never enter state history. */
public record DiagramAgentStepRecord(
        int stepNumber,
        String actionType,
        String toolName,
        String outcomeCode,
        String beforeDraftDigest,
        String afterDraftDigest
) {

    public DiagramAgentStepRecord {
        if (stepNumber <= 0) {
            throw new IllegalArgumentException("agent step number must be positive");
        }
        actionType = text(actionType);
        toolName = text(toolName);
        outcomeCode = text(outcomeCode);
        beforeDraftDigest = text(beforeDraftDigest);
        afterDraftDigest = text(afterDraftDigest);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
