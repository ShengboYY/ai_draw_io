package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentToolPort;
import org.zipp.ai.application.turn.agent.DiagramDraftStore;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReviewPort;
import org.zipp.ai.application.turn.agent.DraftCellMutation;
import org.zipp.ai.application.turn.agent.DraftInspectionScope;
import org.zipp.ai.application.turn.agent.InspectDraftRequest;
import org.zipp.ai.application.turn.agent.PatchDraftRequest;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.infrastructure.turn.agent.DefaultDiagramDraftVisualReviewAdapter;
import org.zipp.ai.infrastructure.turn.agent.DefaultDiagramAgentToolAdapter;
import org.zipp.ai.infrastructure.turn.agent.InMemoryDiagramDraftStore;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDiagramAgentToolAdapterTest {

    @Test
    void wrapsTheDelegatedReviewerInItsOwnTelemetryStep() throws Exception {
        AgentUsageTelemetryService telemetry = mock(AgentUsageTelemetryService.class);
        when(telemetry.recordStep(eq("plain_visual_review_agent"), any()))
                .thenAnswer(invocation ->
                        ((Callable<?>) invocation.getArgument(1)).call());
        DefaultDiagramDraftVisualReviewAdapter reviews =
                new DefaultDiagramDraftVisualReviewAdapter(
                        command -> CanvasVisualReviewResult.builder()
                                .available(true)
                                .summary("Readable")
                                .issues(List.of())
                                .build(),
                        telemetry);
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        var draft = store.create(attempt(), graph(
                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\"/>"));

        reviews.review(new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow"), draft);

        verify(telemetry).recordStep(eq("plain_visual_review_agent"), any());
    }

    @Test
    void springUsesTheDraftStoreConstructor() {
        new ApplicationContextRunner()
                .withBean(DiagramDraftStore.class, InMemoryDiagramDraftStore::new)
                .withUserConfiguration(DefaultDiagramAgentToolAdapter.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(DefaultDiagramAgentToolAdapter.class)
                        .hasSingleBean(DiagramAgentToolPort.class));
    }

    @Test
    void springKeepsTheVisualReviewerSeparateFromTheDraftToolAdapter() {
        new ApplicationContextRunner()
                .withBean(DiagramDraftStore.class, InMemoryDiagramDraftStore::new)
                .withBean(ICanvasVisualReviewer.class, () -> command ->
                        CanvasVisualReviewResult.builder()
                                .available(true)
                                .summary("Readable")
                                .issues(List.of())
                                .build())
                .withUserConfiguration(
                        DefaultDiagramDraftVisualReviewAdapter.class,
                        DefaultDiagramAgentToolAdapter.class)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(DiagramDraftVisualReviewPort.class)
                            .hasSingleBean(DiagramAgentToolPort.class);
                    assertThat(context.getBean(DiagramDraftVisualReviewPort.class)
                            .defersToClientRenderedEvidence()).isTrue();
                });
    }

    @Test
    void createsAndInspectsTargetCellsWithoutRunningQualityAnalysis() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        DefaultDiagramAgentToolAdapter tools = new DefaultDiagramAgentToolAdapter(store);
        FencedAttempt attempt = attempt();
        PlainDrawPlan plan = new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow");

        var created = tools.execute(attempt, plan, new CreateDraftRequest(graph(
                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/>"
                        + "</mxCell>")));
        var inspected = tools.execute(attempt, plan, new InspectDraftRequest(
                created.draft().ref(),
                DraftInspectionScope.TARGET_CELLS,
                List.of("node-a"),
                ""));

        assertThat(created.success()).isTrue();
        assertThat(created.structure().nodeCount()).isEqualTo(1);
        assertThat(created.structure().edgeCount()).isZero();
        assertThat(inspected.cells()).hasSize(1);
        assertThat(inspected.cells().get(0).rawXml()).contains("id=\"node-a\"");
        assertThat(inspected.canvasXml()).isEmpty();
    }

    @Test
    void refusesCreateOutsideCreateRouteAndSemanticChangesInLayoutRoute() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        DefaultDiagramAgentToolAdapter tools = new DefaultDiagramAgentToolAdapter(store);
        FencedAttempt attempt = attempt();
        PlainDrawPlan edit = new PlainDrawPlan(PlainDrawAction.EDIT, "rename a node");

        assertThat(tools.execute(attempt, edit, new CreateDraftRequest(graph(""))).outcomeCode())
                .isEqualTo("TOOL_NOT_ALLOWED");

        var imported = store.create(attempt, graph(
                "<mxCell id=\"node-a\" value=\"Before\" style=\"rounded=1\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/>"
                        + "</mxCell>"));
        PlainDrawPlan layout = new PlainDrawPlan(PlainDrawAction.LAYOUT, "move the node");
        var changed = tools.execute(attempt, layout, new PatchDraftRequest(
                imported.ref(),
                imported.digest(),
                List.of(DraftCellMutation.replace(
                        "node-a",
                        "<mxCell id=\"node-a\" value=\"After\" style=\"rounded=1\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"20\" y=\"20\" width=\"80\" height=\"40\" as=\"geometry\"/>"
                                + "</mxCell>"))));

        assertThat(changed.success()).isFalse();
        assertThat(changed.outcomeCode()).isEqualTo("LAYOUT_SEMANTIC_CHANGE");
    }

    @Test
    void rendersDraftPngBeforeCallingTheExistingVisualReviewer() {
        AtomicReference<CanvasVisualReviewCommand> captured = new AtomicReference<>();
        DefaultDiagramDraftVisualReviewAdapter reviews =
                new DefaultDiagramDraftVisualReviewAdapter(command -> {
                    captured.set(command);
                    return CanvasVisualReviewResult.builder()
                            .available(true)
                            .summary("Readable")
                            .issues(List.of())
                            .reviewerVersion("test-reviewer")
                            .build();
                });
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        var draft = store.create(attempt(), graph(
                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                        + "as=\"geometry\"/></mxCell>"));

        var result = reviews.review(
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow"),
                draft);

        assertThat(result.decision()).isEqualTo("APPROVE");
        assertThat(captured.get().getAfterImageDataUrl())
                .startsWith("data:image/png;base64,");
        assertThat(captured.get().getRendererVersion())
                .startsWith("plain-agent-draft-png-");
        assertThat(captured.get().getAnalyzerEvidence()).isEmpty();
        assertThat(captured.get().getCanvasSummary()).isEqualTo("nodes=1, edges=0");
    }

    @Test
    void keepsMixedButIndividuallyGroundedLocalIssuesRepairable() {
        DefaultDiagramDraftVisualReviewAdapter reviews =
                new DefaultDiagramDraftVisualReviewAdapter(command ->
                        CanvasVisualReviewResult.builder()
                                .available(true)
                                .summary("Repair the containers and connector.")
                                .issues(List.of(
                                        visualIssue(
                                                CanvasVisualIssueType.LAYOUT_HIERARCHY,
                                                List.of("runtime", "engine")),
                                        visualIssue(
                                                CanvasVisualIssueType.EDGE_TRACEABILITY,
                                                List.of("edge-1"))))
                                .reviewerVersion("test-reviewer")
                                .build());
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        var draft = store.create(attempt(), graph(
                "<mxCell id=\"runtime\" value=\"Runtime\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"10\" width=\"180\" height=\"100\" "
                        + "as=\"geometry\"/></mxCell>"
                        + "<mxCell id=\"engine\" value=\"Engine\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"160\" width=\"180\" height=\"100\" "
                        + "as=\"geometry\"/></mxCell>"
                        + "<mxCell id=\"edge-1\" edge=\"1\" parent=\"1\" "
                        + "source=\"runtime\" target=\"engine\">"
                        + "<mxGeometry relative=\"1\" as=\"geometry\"/></mxCell>"));

        var result = reviews.review(
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a JVM architecture"),
                draft);

        assertThat(result.decision()).isEqualTo("REPAIR");
        assertThat(result.groundingConflict()).isEmpty();
        assertThat(result.issues())
                .flatExtracting(issue -> issue.targetCellIds())
                .containsExactlyInAnyOrder("runtime", "engine", "edge-1");
    }

    private CanvasVisualIssue visualIssue(
            CanvasVisualIssueType type,
            List<String> targetCellIds
    ) {
        return CanvasVisualIssue.builder()
                .type(type)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .targetCellIds(targetCellIds)
                .anchorLabels(List.of("visible"))
                .region("center")
                .evidence("Visible local issue")
                .repairInstruction("Apply one bounded local repair.")
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
    }

    private String graph(String cells) {
        return "<mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/>" + cells + "</root></mxGraphModel>";
    }

    private FencedAttempt attempt() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, now.plusSeconds(60), 60_000),
                1,
                "input-binding",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }
}
