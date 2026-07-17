package org.zipp.ai.domain.agent.model.valobj.visualreview;

import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class CanvasVisualIssue {

    private CanvasVisualIssueType type;
    private CanvasVisualIssueSeverity severity;
    private List<String> targetCellIds;
    private List<String> anchorLabels;
    private String region;
    private String evidence;
    private String repairInstruction;
    private CanvasVisualRepairScope repairScope;

    /**
     * Bounds model-authored text before it can reach a repair prompt or stream response.
     */
    public CanvasVisualIssue boundedCopy() {
        List<String> cellIds = targetCellIds == null
                ? Collections.emptyList()
                : targetCellIds.stream().limit(5).map(id -> abbreviate(id, 80)).toList();
        List<String> labels = anchorLabels == null
                ? Collections.emptyList()
                : anchorLabels.stream().limit(3).map(label -> abbreviate(label, 80)).toList();
        return CanvasVisualIssue.builder()
                .type(type)
                .severity(severity)
                .targetCellIds(cellIds)
                .anchorLabels(labels)
                .region(abbreviate(region, 32))
                .evidence(abbreviate(evidence, 300))
                .repairInstruction(abbreviate(repairInstruction, 300))
                .repairScope(repairScope)
                .build();
    }

    private String abbreviate(String value, int maxLength) {
        return StringUtils.abbreviate(StringUtils.defaultString(value).trim(), maxLength);
    }
}
