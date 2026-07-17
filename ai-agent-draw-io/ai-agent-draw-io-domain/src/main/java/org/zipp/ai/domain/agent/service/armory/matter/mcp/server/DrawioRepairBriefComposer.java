package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a deterministic canvas analysis into the drawing loop's next-step instruction.
 * The drawer model reads this from every mutation tool response. Only structural failures may
 * start deterministic self-repair; visual findings remain analysis evidence for the VLM reviewer.
 */
final class DrawioRepairBriefComposer {

    private static final int MAX_ITEMS = 5;
    private static final Set<CanvasIssueType> HARD_REPAIR_TYPES = EnumSet.of(
            CanvasIssueType.INVALID_XML,
            CanvasIssueType.DUP_ID,
            CanvasIssueType.MISSING_GEOMETRY,
            CanvasIssueType.BROKEN_EDGE);

    static final String FINISH_SIGNAL =
            "APPLIED. No blocking issues. Do not call another drawing tool; finish with a short user-facing summary.";

    private DrawioRepairBriefComposer() {
    }

    static String compose(CanvasAnalysis analysis) {
        List<CanvasAnalysisIssue> blocking = analysis.getIssues().stream()
                .filter(DrawioRepairBriefComposer::shouldTriggerRepair)
                .toList();
        if (blocking.isEmpty()) {
            return FINISH_SIGNAL;
        }

        StringBuilder brief = new StringBuilder("APPLIED (canvas updated), but ")
                .append(blocking.size())
                .append(" blocking issue(s) remain:\n");
        int index = 0;
        for (CanvasAnalysisIssue issue : blocking) {
            if (index >= MAX_ITEMS) {
                brief.append("... and ").append(blocking.size() - MAX_ITEMS).append(" more.\n");
                break;
            }
            brief.append(++index)
                    .append(". [").append(issue.getSeverity()).append("] ")
                    .append(issue.getMessage())
                    .append(" -> ").append(suggestionFor(issue))
                    .append("\n");
        }
        brief.append("If repair budget remains, fix these with ONE modify_diagram call (mode=patch, only the affected cells)")
                .append(" or mode=replace_cells for id-based structural fixes; never redraw the diagram.")
                .append(" If the budget is exhausted, finish and briefly mention what is left.");
        return brief.toString();
    }

    private static boolean shouldTriggerRepair(CanvasAnalysisIssue issue) {
        return issue != null
                && "critical".equals(issue.getSeverity())
                && HARD_REPAIR_TYPES.contains(issue.getType());
    }

    private static String suggestionFor(CanvasAnalysisIssue issue) {
        List<String> ids = issue.getTargetCellIds() == null ? List.of() : issue.getTargetCellIds();
        String first = ids.isEmpty() ? "the affected cell" : ids.get(0);
        return switch (issue.getType()) {
            case BROKEN_EDGE -> "point edge " + first + " source/target at cell ids that exist in the same XML, or delete the edge";
            case MISSING_GEOMETRY -> "give cell " + first + " a complete mxGeometry (x, y, width, height, as=\"geometry\")";
            case DUP_ID -> "assign cell " + first + " a unique id and update any edges referencing it";
            case INVALID_XML -> "re-emit well-formed mxCell XML for the affected region";
            default -> throw new IllegalArgumentException(
                    "Visual issue must not enter deterministic repair: " + issue.getType());
        };
    }
}
