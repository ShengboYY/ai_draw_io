package org.zipp.ai.domain.agent.model.valobj.analysis;

import java.util.List;
import java.util.Set;

public record DiagramQualityProfile(
        String version,
        DiagramType diagramType,
        LayoutFamily defaultLayoutFamily,
        Set<LayoutFamily> allowedLayoutFamilies,
        Set<CanvasIssueType> enabledIssueTypes,
        Set<CanvasIssueType> automaticallyRepairableIssueTypes,
        List<String> visualRubric
) {
    public DiagramQualityProfile {
        allowedLayoutFamilies = Set.copyOf(allowedLayoutFamilies);
        enabledIssueTypes = Set.copyOf(enabledIssueTypes);
        automaticallyRepairableIssueTypes = Set.copyOf(automaticallyRepairableIssueTypes);
        visualRubric = List.copyOf(visualRubric);
    }

    public boolean enables(CanvasIssueType issueType) {
        return enabledIssueTypes.contains(issueType);
    }

    public boolean allowsAutomaticRepair(CanvasIssueType issueType) {
        return automaticallyRepairableIssueTypes.contains(issueType);
    }
}
