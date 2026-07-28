package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Bounded deterministic issue projected into the next agent observation. */
public record DiagramDraftIssue(
        String type,
        String severity,
        List<String> targetCellIds,
        String message
) {

    public DiagramDraftIssue {
        type = normalized(type, "UNKNOWN");
        severity = normalized(severity, "unknown");
        targetCellIds = List.copyOf(targetCellIds == null ? List.of() : targetCellIds);
        message = normalized(message, "");
    }

    private static String normalized(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isBlank() ? fallback : result;
    }
}
