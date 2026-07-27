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
        brief.append("[Visual Review Continuation]\n")
                .append("This is reviewer feedback for the existing Draw Agent session, not a new user request.\n")
                .append("Original task: ")
                .append(safeText(originalUserTask, 300))
                .append("\nReviewed canvas: version=")
                .append(reviewedVersion == null ? "unknown" : reviewedVersion)
                .append(", contentHash=")
                .append(safeText(reviewedContentHash, 100))
                .append("\nReviewer evidence (maximum 3 issues):\n");

        List<CanvasVisualIssue> safeIssues = issues == null ? Collections.emptyList() : issues;
        for (int index = 0; index < Math.min(3, safeIssues.size()); index++) {
            CanvasVisualIssue issue = safeIssues.get(index).boundedCopy();
            brief.append(index + 1).append(". ")
                    .append(issue.getType()).append("/").append(issue.getSeverity())
                    .append("; targets=").append(safeText(String.valueOf(issue.getTargetCellIds()), 220))
                    .append("; anchors=").append(safeText(String.valueOf(issue.getAnchorLabels()), 180))
                    .append("; region=").append(issue.getRegion())
                    .append("; evidence=").append(safeText(issue.getEvidence(), 180))
                    .append("; suggestedFix=").append(safeText(issue.getRepairInstruction(), 180))
                    .append("\n");
        }
        brief.append("Constraints:\n")
                .append("- Make one apply_visual_repair call containing one to three bounded operations.\n")
                .append("- Preserve every unmentioned id, label, relationship, geometry, and style.\n")
                .append("- Use only grounded SET_GEOMETRY, SET_STYLE, REROUTE_EDGE, RECONNECT_EDGE, ALIGN_CELLS, or DISTRIBUTE_CELLS actions.\n")
                .append("- Never submit XML or call a general drawing tool; stop after one saved mutation.");
        return brief.toString();
    }

    private String safeText(String value, int maxLength) {
        String withoutImages = DATA_URL.matcher(StringUtils.defaultString(value)).replaceAll("[image omitted]");
        return StringUtils.abbreviate(withoutImages.replaceAll("[\\r\\n]+", " ").trim(), maxLength);
    }
}
