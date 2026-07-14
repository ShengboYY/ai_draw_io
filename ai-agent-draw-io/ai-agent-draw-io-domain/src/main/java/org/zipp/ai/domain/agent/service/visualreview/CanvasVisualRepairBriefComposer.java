package org.zipp.ai.domain.agent.service.visualreview;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class CanvasVisualRepairBriefComposer {

    private static final Pattern DATA_URL = Pattern.compile("data:image/[^\\s]+", Pattern.CASE_INSENSITIVE);

    public String compose(String originalUserTask,
                          Long reviewedVersion,
                          String reviewedContentHash,
                          List<CanvasVisualIssue> issues) {
        StringBuilder brief = new StringBuilder();
        brief.append("Perform one scoped visual repair for the original task: ")
                .append(safeText(originalUserTask, 500))
                .append("\nReviewed canvas: version=")
                .append(reviewedVersion == null ? "unknown" : reviewedVersion)
                .append(", contentHash=")
                .append(safeText(reviewedContentHash, 100))
                .append("\nFix only these visible issues:\n");

        List<CanvasVisualIssue> safeIssues = issues == null ? Collections.emptyList() : issues;
        for (int index = 0; index < Math.min(3, safeIssues.size()); index++) {
            CanvasVisualIssue issue = safeIssues.get(index).boundedCopy();
            brief.append(index + 1).append(". ")
                    .append(issue.getType()).append("/").append(issue.getSeverity())
                    .append("; anchors=").append(issue.getAnchorLabels())
                    .append("; region=").append(issue.getRegion())
                    .append("; evidence=").append(safeText(issue.getEvidence(), 300))
                    .append("; instruction=").append(safeText(issue.getRepairInstruction(), 300))
                    .append("\n");
        }
        brief.append("Preserve every unmentioned id, label, relationship, geometry, and style.");
        return brief.toString();
    }

    private String safeText(String value, int maxLength) {
        String withoutImages = DATA_URL.matcher(StringUtils.defaultString(value)).replaceAll("[image omitted]");
        return StringUtils.abbreviate(withoutImages.replaceAll("[\\r\\n]+", " ").trim(), maxLength);
    }
}
