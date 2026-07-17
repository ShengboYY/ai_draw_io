package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewGrounding;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewGroundingGuard;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasVisualReviewGroundingGuardTest {

    private final CanvasVisualReviewGroundingGuard guard = new CanvasVisualReviewGroundingGuard();

    @Test
    public void authorizesOnlyTheGroundedEdgeAndItsVisualFields() {
        CanvasVisualReviewGrounding grounding = guard.ground(analysis(), result(issue(
                CanvasVisualIssueType.EDGE_TRACEABILITY, CanvasVisualRepairScope.LOCAL, List.of("edge-1"))));

        assertFalse(grounding.hasConflict());
        assertEquals(Set.of("edge-1"), grounding.authorization().allowedCellIds());
        assertEquals(Set.of(CanvasField.STYLE, CanvasField.WAYPOINTS), grounding.authorization().allowedFields());
        assertEquals(1, grounding.returnedTargetCount());
        assertEquals(1, grounding.validTargetCount());
        assertEquals(0, grounding.invalidTargetCount());
        assertEquals(2, grounding.nodeCount());
        assertEquals(1, grounding.edgeCount());
    }

    @Test
    public void rejectsAnEdgeFindingThatTargetsOnlyANode() {
        CanvasVisualReviewGrounding grounding = guard.ground(analysis(), result(issue(
                CanvasVisualIssueType.EDGE_TRACEABILITY, CanvasVisualRepairScope.LOCAL, List.of("node-1"))));

        assertTrue(grounding.hasConflict());
        assertEquals("edge_issue_without_edge_target", grounding.conflictReason());
        assertTrue(grounding.authorization().allowedCellIds().isEmpty());
    }

    @Test
    public void rejectsAnEdgeFindingThatMixesEdgeAndNodeTargets() {
        CanvasVisualReviewGrounding grounding = guard.ground(analysis(), result(issue(
                CanvasVisualIssueType.EDGE_TRACEABILITY,
                CanvasVisualRepairScope.LOCAL,
                List.of("edge-1", "node-1"))));

        assertEquals("edge_issue_with_non_edge_target", grounding.conflictReason());
        assertTrue(grounding.authorization().allowedCellIds().isEmpty());
    }

    @Test
    public void rejectsMixedIssuesThatNeedDifferentFieldCapabilities() {
        CanvasVisualReviewGrounding grounding = guard.ground(analysis(), result(
                issue(CanvasVisualIssueType.TEXT_READABILITY,
                        CanvasVisualRepairScope.LOCAL, List.of("node-1")),
                issue(CanvasVisualIssueType.EDGE_TRACEABILITY,
                        CanvasVisualRepairScope.LOCAL, List.of("edge-1"))));

        assertEquals("mixed_target_capabilities", grounding.conflictReason());
        assertTrue(grounding.authorization().allowedCellIds().isEmpty());
        assertTrue(grounding.authorization().allowedFields().isEmpty());
    }

    @Test
    public void labelsNeverGrantMutationAuthorityWithoutTargetIds() {
        CanvasVisualIssue issue = issue(CanvasVisualIssueType.LAYOUT_HIERARCHY,
                CanvasVisualRepairScope.LOCAL, List.of());
        issue.setAnchorLabels(List.of("API"));

        CanvasVisualReviewGrounding grounding = guard.ground(analysis(), result(issue));

        assertTrue(grounding.hasConflict());
        assertEquals("local_issue_without_target", grounding.conflictReason());
        assertTrue(grounding.authorization().allowedCellIds().isEmpty());
    }

    @Test
    public void unknownAndWholeCanvasTargetsFailClosed() {
        CanvasVisualReviewGrounding unknown = guard.ground(analysis(), result(issue(
                CanvasVisualIssueType.TEXT_READABILITY, CanvasVisualRepairScope.LOCAL, List.of("missing"))));
        CanvasVisualReviewGrounding wholeCanvas = guard.ground(analysis(), result(issue(
                CanvasVisualIssueType.LAYOUT_HIERARCHY, CanvasVisualRepairScope.WHOLE_CANVAS, List.of("node-1"))));

        assertEquals("unknown_target_cell", unknown.conflictReason());
        assertEquals(1, unknown.invalidTargetCount());
        assertTrue(unknown.authorization().allowedCellIds().isEmpty());
        assertEquals("whole_canvas_not_automatable", wholeCanvas.conflictReason());
        assertTrue(wholeCanvas.authorization().allowedCellIds().isEmpty());
    }

    @Test
    public void targetOutsideTheBoundedManifestNeverReceivesAuthority() {
        List<CanvasCellData> cells = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> CanvasCellData.builder()
                        .id("node-" + index).label("Node " + index).kind("node").build())
                .toList();
        CanvasAnalysis analysis = CanvasAnalysis.builder().cells(cells).build();

        CanvasVisualReviewGrounding grounding = guard.ground(analysis, result(issue(
                CanvasVisualIssueType.TEXT_READABILITY,
                CanvasVisualRepairScope.LOCAL,
                List.of("node-101"))));

        assertEquals("target_not_in_manifest", grounding.conflictReason());
        assertTrue(grounding.authorization().allowedCellIds().isEmpty());
    }

    private CanvasAnalysis analysis() {
        return CanvasAnalysis.builder().cells(List.of(
                CanvasCellData.builder().id("node-1").label("API").kind("node").build(),
                CanvasCellData.builder().id("node-2").label("Database").kind("node").build(),
                CanvasCellData.builder().id("edge-1").label("query").kind("edge")
                        .source("node-1").target("node-2").build())).build();
    }

    private CanvasVisualReviewResult result(CanvasVisualIssue... issues) {
        return CanvasVisualReviewResult.builder().available(true).issues(List.of(issues)).build();
    }

    private CanvasVisualIssue issue(CanvasVisualIssueType type,
                                    CanvasVisualRepairScope scope,
                                    List<String> targetCellIds) {
        return CanvasVisualIssue.builder()
                .type(type)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .targetCellIds(targetCellIds)
                .anchorLabels(List.of("API"))
                .region("center")
                .evidence("Visible issue")
                .repairInstruction("Apply a local correction")
                .repairScope(scope)
                .build();
    }
}
