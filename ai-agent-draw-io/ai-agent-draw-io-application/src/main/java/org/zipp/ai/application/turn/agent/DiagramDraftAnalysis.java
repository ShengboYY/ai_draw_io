package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Deterministic structural/layout feedback used by the model and final submission policy. */
public record DiagramDraftAnalysis(
        boolean structurallyValid,
        boolean readyForSubmission,
        int nodeCount,
        int edgeCount,
        String severity,
        List<DiagramDraftIssue> issues
) {

    public DiagramDraftAnalysis {
        if (nodeCount < 0 || edgeCount < 0) {
            throw new IllegalArgumentException("diagram counts must not be negative");
        }
        severity = severity == null || severity.isBlank() ? "unknown" : severity.trim();
        issues = List.copyOf(issues == null ? List.of() : issues);
    }
}
