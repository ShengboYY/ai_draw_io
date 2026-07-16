package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
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
    public void eligibleBlockingIssueRequestsFirstRepair() {
        assertEquals(CanvasVisualReviewDecision.REPAIR,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.EDGE_TRACEABILITY,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void eligiblePostRepairIssueRequestsSecondRepair() {
        assertEquals(CanvasVisualReviewDecision.REPAIR,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.EDGE_TRACEABILITY,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_REPAIR, 1));
    }

    @Test
    public void repairAuthorityRequiresTheExactStageRoundPair() {
        CanvasVisualReviewResult result = available(List.of(issue(CanvasVisualIssueType.EDGE_TRACEABILITY,
                CanvasVisualIssueSeverity.MAJOR)), false);

        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(result, CanvasVisualReviewStage.POST_MUTATION, 1));
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(result, CanvasVisualReviewStage.POST_REPAIR, 0));
    }

    @Test
    public void semanticUncertaintyRequiresHumanReview() {
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.DOMAIN_UNCERTAINTY,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void missingRequestedElementDoesNotReceiveVisualRepairAuthority() {
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.MISSING_REQUESTED_ELEMENT,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void invisibleTaskDoesNotReceiveVisualRepairAuthority() {
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.TASK_NOT_VISIBLE,
                        CanvasVisualIssueSeverity.MAJOR)), false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void minorSemanticRiskCannotHitchhikeOnSafeLayoutRepair() {
        List<CanvasVisualIssue> issues = List.of(
                issue(CanvasVisualIssueType.WRONG_REQUESTED_RELATIONSHIP, CanvasVisualIssueSeverity.MINOR),
                issue(CanvasVisualIssueType.TEXT_READABILITY, CanvasVisualIssueSeverity.MAJOR));

        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(issues, false), CanvasVisualReviewStage.POST_MUTATION, 0));
    }

    @Test
    public void finalVerificationNeverStartsThirdRepair() {
        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue(CanvasVisualIssueType.TEXT_READABILITY,
                        CanvasVisualIssueSeverity.CRITICAL)), false), CanvasVisualReviewStage.VERIFY_ONLY, 2));
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

    @Test
    public void wholeCanvasRedrawInstructionRequiresHumanReview() {
        CanvasVisualIssue issue = issue(CanvasVisualIssueType.LAYOUT_HIERARCHY, CanvasVisualIssueSeverity.MAJOR);
        issue.setRepairInstruction("Start over from scratch.");
        issue.setRepairScope(CanvasVisualRepairScope.WHOLE_CANVAS);

        assertEquals(CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW,
                policy.decide(available(List.of(issue), false), CanvasVisualReviewStage.POST_MUTATION, 0));
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
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
    }
}
