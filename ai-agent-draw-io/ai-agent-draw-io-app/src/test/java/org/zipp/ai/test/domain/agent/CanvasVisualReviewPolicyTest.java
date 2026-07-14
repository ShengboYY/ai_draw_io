package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class CanvasVisualReviewPolicyTest {

    private final CanvasVisualReviewPolicy policy = new CanvasVisualReviewPolicy();

    @Test
    public void unavailableReviewFailsOpen() {
        assertEquals(CanvasVisualReviewDecision.UNAVAILABLE,
                policy.decide(CanvasVisualReviewResult.unavailable("timeout"), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void emptyIssuesAreApproved() {
        assertEquals(CanvasVisualReviewDecision.APPROVE,
                policy.decide(available(List.of(), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void minorIssuesAreNotes() {
        assertEquals(CanvasVisualReviewDecision.APPROVE_WITH_NOTES,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.TEXT_READABILITY,
                        CanvasVisualIssueSeverity.MINOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void eligibleBlockingIssueRequestsOneRepair() {
        assertEquals(CanvasVisualReviewDecision.REPAIR,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.EDGE_TRACEABILITY,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void semanticUncertaintyRequiresHumanReview() {
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.DOMAIN_UNCERTAINTY,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void verifyOnlyNeverStartsAnotherRepair() {
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.TEXT_READABILITY,
                        CanvasVisualIssueSeverity.CRITICAL)), false), CanvasVisualReviewStage.VERIFY_ONLY, 1));
    }

    @Test
    public void tooManyBlockingIssuesRequireHumanReview() {
        List<CanvasVisualIssue> issues = List.of(
                issue(CanvasVisualIssueType.TEXT_READABILITY, CanvasVisualIssueSeverity.MAJOR),
                issue(CanvasVisualIssueType.LAYOUT_HIERARCHY, CanvasVisualIssueSeverity.MAJOR),
                issue(CanvasVisualIssueType.EDGE_TRACEABILITY, CanvasVisualIssueSeverity.MAJOR),
                issue(CanvasVisualIssueType.STYLE_COHERENCE, CanvasVisualIssueSeverity.MAJOR));

        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(issues, false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    private CanvasVisualReviewResult available(List<CanvasVisualIssue> issues, boolean humanReview) {
        return CanvasVisualReviewResult.builder()
                .available(true)
                .summary("reviewed")
                .issues(issues)
                .recommendedHumanReview(humanReview)
                .build();
    }

    private CanvasVisualIssue issue(CanvasVisualIssueType type, CanvasVisualIssueSeverity severity) {
        return CanvasVisualIssue.builder()
                .type(type)
                .severity(severity)
                .anchorLabels(List.of("API"))
                .region("center")
                .evidence("The visible edge is hard to follow.")
                .repairInstruction("Route the edge around the node.")
                .build();
    }
}
