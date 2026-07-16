package org.zipp.ai.domain.agent.service.visualreview;

import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class CanvasVisualReviewPolicy {

    public static final int MAX_AUTOMATIC_REPAIR_ROUNDS = 2;
    private static final int MAX_AUTOMATIC_REPAIR_ISSUES = 3;
    private static final Set<CanvasVisualIssueType> AUTOMATIC_REPAIR_TYPES = EnumSet.of(
            CanvasVisualIssueType.TEXT_READABILITY,
            CanvasVisualIssueType.LAYOUT_HIERARCHY,
            CanvasVisualIssueType.EDGE_TRACEABILITY,
            CanvasVisualIssueType.STYLE_COHERENCE);

    public CanvasVisualReviewDecision decide(CanvasVisualReviewResult result,
                                             CanvasVisualReviewStage stage,
                                             int completedRepairRounds) {
        // Review failure is fail-open: deterministic validation already owns canvas safety.
        if (result == null || !result.isAvailable()) {
            return CanvasVisualReviewDecision.UNAVAILABLE;
        }

        List<CanvasVisualIssue> issues = result.safeIssues();
        if (issues.isEmpty()) {
            return CanvasVisualReviewDecision.APPROVE;
        }
        if (result.isRecommendedHumanReview()
                || issues.stream().anyMatch(issue -> issue.getType() == CanvasVisualIssueType.DOMAIN_UNCERTAINTY)) {
            return CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
        }

        List<CanvasVisualIssue> blocking = issues.stream()
                .filter(issue -> issue.getSeverity() != null && issue.getSeverity().isBlocking())
                .toList();
        if (blocking.isEmpty()) {
            return CanvasVisualReviewDecision.APPROVE_WITH_NOTES;
        }

        // VLM findings are evidence only. This policy grants at most two local repair mutations.
        boolean repairCheckpoint = (stage == CanvasVisualReviewStage.POST_MUTATION && completedRepairRounds == 0)
                || (stage == CanvasVisualReviewStage.POST_REPAIR && completedRepairRounds == 1);
        if (!repairCheckpoint
                || issues.size() > MAX_AUTOMATIC_REPAIR_ISSUES
                // Every issue entering the repair brief must be safe; a minor semantic issue
                // cannot hitchhike on a repairable blocking layout issue.
                || issues.stream().anyMatch(issue -> !AUTOMATIC_REPAIR_TYPES.contains(issue.getType()))
                || issues.stream().anyMatch(issue -> issue.getRepairScope() != CanvasVisualRepairScope.LOCAL)) {
            return CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
        }
        return CanvasVisualReviewDecision.REPAIR;
    }
}
