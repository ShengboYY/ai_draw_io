package org.zipp.ai.domain.agent.model.valobj.analysis;

import java.util.Map;

public record CanvasIssueEvidence(
        CanvasEvidenceSource source,
        String ruleId,
        Map<String, Object> attributes
) {
    public CanvasIssueEvidence {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
