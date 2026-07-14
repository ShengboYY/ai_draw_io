package org.zipp.ai.domain.agent.service.visualreview;

import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class CanvasVisualReviewPolicy {

    private static final int MAX_AUTOMATIC_REPAIR_ISSUES = 3;
    private static final Set<CanvasVisualIssueType> AUTOMATIC_REPAIR_TYPES = EnumSet.of(
            CanvasVisualIssueType.TASK_NOT_VISIBLE,
            CanvasVisualIssueType.MISSING_REQUESTED_ELEMENT,
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

        // Only the first post-mutation review may authorize a bounded repair.
        if (stage != CanvasVisualReviewStage.POST_MUTATION || completedRepairRounds > 0
                || blocking.size() > MAX_AUTOMATIC_REPAIR_ISSUES
                || blocking.stream().anyMatch(issue -> !AUTOMATIC_REPAIR_TYPES.contains(issue.getType()))) {
            return CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
        }
        return CanvasVisualReviewDecision.REPAIR;
    }
}
