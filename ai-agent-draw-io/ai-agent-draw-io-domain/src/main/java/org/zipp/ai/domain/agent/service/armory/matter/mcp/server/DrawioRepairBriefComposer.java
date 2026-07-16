package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;

import java.util.Comparator;
import java.util.List;

/**
 * Turns a deterministic canvas analysis into the drawing loop's next-step instruction.
 * The drawer model reads this from every mutation tool response: blocking issues become
 * numbered, executable repair directives; a clean canvas becomes an explicit finish signal.
 * Minor polish issues are deliberately excluded so the loop converges instead of gold-plating.
 */
final class DrawioRepairBriefComposer {

    private static final int MAX_ITEMS = 5;

    static final String FINISH_SIGNAL =
            "APPLIED. No blocking issues. Do not call another drawing tool; finish with a short user-facing summary.";

    private DrawioRepairBriefComposer() {
    }

    static String compose(CanvasAnalysis analysis) {
        List<CanvasAnalysisIssue> blocking = analysis.getIssues().stream()
                .filter(issue -> isBlocking(issue.getSeverity()))
                .sorted(Comparator.comparingInt(issue -> "critical".equals(issue.getSeverity()) ? 0 : 1))
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
        appendPolishNotes(brief, analysis);
        brief.append("If repair budget remains, fix these with ONE modify_diagram call (mode=patch, only the affected cells)")
                .append(" or optimize_diagram(mode=route_only, targetEdgeIds=[only the cited edge ids]) for edge-only fixes; never redraw the diagram.")
                .append(" If the budget is exhausted, finish and briefly mention what is left.");
        return brief.toString();
    }

    /**
     * Minor style observations ride along only when a repair round is happening anyway;
     * they must never trigger a round on their own or block the finish signal.
     */
    private static void appendPolishNotes(StringBuilder brief, CanvasAnalysis analysis) {
        List<CanvasAnalysisIssue> minors = analysis.getIssues().stream()
                .filter(issue -> "minor".equals(issue.getSeverity()))
                .filter(issue -> issue.getType() != org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType.REMOVABLE_WAYPOINT)
                .limit(2)
                .toList();
        if (minors.isEmpty()) {
            return;
        }
        brief.append("Polish (optional, only while touching those cells anyway): ");
        for (int i = 0; i < minors.size(); i++) {
            if (i > 0) {
                brief.append("; ");
            }
            brief.append(minors.get(i).getMessage());
        }
        brief.append("\n");
    }

    private static boolean isBlocking(String severity) {
        return "critical".equals(severity) || "major".equals(severity);
    }

    private static String suggestionFor(CanvasAnalysisIssue issue) {
        List<String> ids = issue.getTargetCellIds() == null ? List.of() : issue.getTargetCellIds();
        String first = ids.isEmpty() ? "the affected cell" : ids.get(0);
        String second = ids.size() > 1 ? ids.get(1) : "the blocking node";
        return switch (issue.getType()) {
            case NODE_OVERLAP -> "move one of cells " + ids + " to a free grid slot; keep a clear channel of >=120px horizontally / >=60px vertically";
            case EDGE_NODE_CROSSING -> "reroute edge " + first + " around node " + second
                    + ": add 2-3 orthogonal waypoints with 20-30px clearance, or route along an outer gutter";
            case BROKEN_EDGE -> "point edge " + first + " source/target at cell ids that exist in the same XML, or delete the edge";
            case MISSING_GEOMETRY -> "give cell " + first + " a complete mxGeometry (x, y, width, height, as=\"geometry\")";
            case DUP_ID -> "assign cell " + first + " a unique id and update any edges referencing it";
            case INVALID_XML -> "re-emit well-formed mxCell XML for the affected region";
            case OPAQUE_TEXT_BACKGROUND -> "make text cell " + first + " transparent: strokeColor=none;fillColor=none;labelBackgroundColor=none";
            case REMOVABLE_WAYPOINT -> "drop the unnecessary waypoints on edge " + first;
            case TEXT_OVERFLOW -> "shorten the label of " + first + ", move detail to a second smaller line, or enlarge the box height";
            case OVERSIZED_REGION -> "shrink region " + first + " to its content plus ~30px padding, or move its children to fill the empty band";
            case PALETTE_INCOHERENT -> "reuse the existing palette roles instead of introducing new fill colors";
            case UNEVEN_SPACING -> "realign cells " + ids + " to one uniform gap on their shared row/column";
            case EDGE_LABEL_COLLISION -> "call optimize_diagram(mode=route_only, targetEdgeIds=[\"" + first + "\"]) — it reroutes edge " + first
                    + " and repositions its label off " + second + " automatically";
            case PORT_DIRECTION_MISMATCH -> "reattach edge " + first
                    + " to the side that matches its visual flow, then keep the route orthogonal";
            case PARALLEL_EDGE_OVERLAP -> "separate the parallel edges touching " + first
                    + " with distinct waypoints or route one edge through an outer gutter";
            case NODE_SIDE_PORT_CROWDING -> "spread same-side connector ports for edges " + ids
                    + " so arrowheads do not stack on one node side";
            case PORT_CORNER_PROXIMITY -> "move edge " + first
                    + " away from rounded corners by using side-port tracks between 0.25 and 0.75";
            case EDGE_EDGE_CROSSING -> "reroute edges " + ids
                    + " onto separate orthogonal tracks without changing their endpoints";
            case EDGE_COLLINEAR_OVERLAP -> "separate the overlapping segments on edges " + ids
                    + " by assigning distinct tracks";
            case PROTECTED_LANE_INTRUSION -> "move return edge " + first
                    + " out of the protected main-flow lane";
            case RETURN_GUTTER_VIOLATION -> "route return edge " + first
                    + " through an outer gutter beyond the content bounds";
            case EDGE_DIRECTION_MISMATCH -> "make edge " + first
                    + " follow the selected layout direction or mark it as an explicit return path";
            case AMBIGUOUS_EDGE_TRACE -> "simplify edge " + first
                    + " and separate it from the listed conflicting edges " + ids;
            case ANALYSIS_LIMITATION -> "add explicit ports or waypoints to edge " + first
                    + " before attempting deterministic geometric repair";
        };
    }
}
