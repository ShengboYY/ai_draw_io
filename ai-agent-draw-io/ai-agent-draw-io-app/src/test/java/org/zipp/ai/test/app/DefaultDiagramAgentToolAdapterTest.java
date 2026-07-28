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
import org.zipp.ai.application.turn.agent.DraftCellMutation;
import org.zipp.ai.application.turn.agent.DraftInspectionScope;
import org.zipp.ai.application.turn.agent.InspectDraftRequest;
import org.zipp.ai.application.turn.agent.PatchDraftRequest;
import org.zipp.ai.infrastructure.turn.agent.DefaultDiagramAgentToolAdapter;
import org.zipp.ai.infrastructure.turn.agent.InMemoryDiagramDraftStore;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDiagramAgentToolAdapterTest {

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
    void createsAnalyzesAndInspectsTargetCellsWithoutReturningTheWholeXml() {
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
        assertThat(created.analysis().nodeCount()).isEqualTo(1);
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
