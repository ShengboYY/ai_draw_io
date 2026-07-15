package org.zipp.ai.domain.agent.model.valobj.analysis;

import java.util.Set;

public record CanvasQualityIssue(
        String issueId,
        CanvasIssueType type,
        CanvasIssueCategory category,
        CanvasIssueSeverity severity,
        CanvasRepairability repairability,
        double confidence,
        Set<String> targetCellIds,
        CanvasIssueEvidence evidence,
        String message,
        String profileVersion
) {
    public CanvasQualityIssue {
        targetCellIds = targetCellIds == null ? Set.of() : Set.copyOf(targetCellIds);
    }
}
