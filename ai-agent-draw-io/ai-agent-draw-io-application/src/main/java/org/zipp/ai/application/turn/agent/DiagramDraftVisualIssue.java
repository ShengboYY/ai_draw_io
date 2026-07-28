package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Bounded visual evidence returned to the decision model without exposing image payloads. */
public record DiagramDraftVisualIssue(
        String type,
        String severity,
        List<String> targetCellIds,
        String evidence,
        String repairInstruction
) {

    public DiagramDraftVisualIssue {
        type = safe(type);
        severity = safe(severity);
        targetCellIds = List.copyOf(targetCellIds == null ? List.of() : targetCellIds);
        evidence = safe(evidence);
        repairInstruction = safe(repairInstruction);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
