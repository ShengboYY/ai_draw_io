package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.BoundedDiagramAgentRuntime;
import org.zipp.ai.application.turn.agent.CallDiagramTool;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentAction;
import org.zipp.ai.application.turn.agent.DiagramAgentDecisionPort;
import org.zipp.ai.application.turn.agent.SubmitDiagramCandidate;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;
import org.zipp.ai.infrastructure.turn.agent.DefaultDiagramAgentToolAdapter;
import org.zipp.ai.infrastructure.turn.agent.InMemoryDiagramDraftStore;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedDiagramAgentRuntimeTest {

    @Test
    void letsTheModelChooseCreateThenSubmitInsideOneBoundedRun() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        AtomicInteger decisions = new AtomicInteger();
        DiagramAgentDecisionPort decision = (observation, cancellation) -> {
            decisions.incrementAndGet();
            if (observation.state().activeDraft() == null) {
                return new CallDiagramTool(new CreateDraftRequest(graph(
                        "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/>"
                                + "</mxCell>")));
            }
            return new SubmitDiagramCandidate(
                    observation.state().activeDraft().ref(),
                    observation.state().activeDraft().digest(),
                    "Created the flow.");
        };
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                decision, new DefaultDiagramAgentToolAdapter(store), store);

        var result = runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false);

        assertThat(decisions.get()).isEqualTo(2);
        assertThat(result.stepCount()).isEqualTo(2);
        assertThat(result.mutationCount()).isEqualTo(1);
        assertThat(result.canvasXml()).contains("node-a");
        // The working copy is deleted after the XML has been captured for the outer commit seam.
        assertThatThrownBy(() -> store.read(attempt(), result.draftRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DRAFT_ATTEMPT_NOT_FOUND");
    }

    @Test
    void neverAutoSubmitsWhenTheModelRepeatsAnAction() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        DiagramAgentAction repeated = new CallDiagramTool(new CreateDraftRequest(graph(
                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\"/>")));
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                (observation, cancellation) -> repeated,
                new DefaultDiagramAgentToolAdapter(store),
                store);

        assertThatThrownBy(() -> runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAIN_AGENT_REPEATED_ACTION");
    }

    private PlainGenerationRequest request(PlainDrawAction action, String canvasXml) {
        return new PlainGenerationRequest(
                attempt(),
                new BaseTurnContext(
                        new CurrentRequestContext(
                                "turn-1", "diagram-1", new CurrentInstruction("draw a flow")),
                        new AvailableContext<>(
                                new CurrentMessageAttachmentsContext("binding-1", List.of()),
                                "attachments"),
                        new AbsentContext<>("no clarification"),
                        new AvailableContext<>(
                                new TrustedCanvasContext(
                                        canvasXml.isBlank() ? 0 : 1,
                                        0,
                                        "",
                                        1,
                                        "canvas-hash",
                                        canvasXml),
                                "canvas"),
                        new AvailableContext<>(
                                new ValidatedSelectionContext(false, 0), "selection"),
                        new AvailableContext<>(
                                new ConversationContext(List.of(), ""), "conversation"),
                        new AbsentContext<>("no membership"),
                        new AbsentContext<>("no profile"),
                        new AbsentContext<>("no memory"),
                        new ContextDiagnostics(List.of())),
                readSet(),
                new PlainDrawPlan(action, "draw a flow"),
                PlainExecutionProfile.m2SourceFree());
    }

    private ContextReadSet readSet() {
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "NO_CANVAS"),
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
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
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }
}
