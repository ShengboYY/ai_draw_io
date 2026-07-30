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
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.BoundedDiagramAgentRuntime;
import org.zipp.ai.application.turn.agent.CallDiagramTool;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentAction;
import org.zipp.ai.application.turn.agent.DiagramAgentBudget;
import org.zipp.ai.application.turn.agent.DiagramAgentDecisionPort;
import org.zipp.ai.application.turn.agent.DiagramDraftSnapshot;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualIssue;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReview;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReviewPort;
import org.zipp.ai.application.turn.agent.DraftCellMutation;
import org.zipp.ai.application.turn.agent.DraftInspectionScope;
import org.zipp.ai.application.turn.agent.InspectDraftRequest;
import org.zipp.ai.application.turn.agent.PatchDraftRequest;
import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;
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
import org.zipp.ai.infrastructure.turn.agent.AgenticPlainGenerationAdapter;
import org.zipp.ai.infrastructure.turn.agent.DefaultDiagramAgentToolAdapter;
import org.zipp.ai.infrastructure.turn.agent.InMemoryDiagramDraftStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedDiagramAgentRuntimeTest {

    @Test
    void tracesSkillLoadingAndPublishesTheCandidateTerminalOnlyOnce() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        List<PlainAgentTraceEvent> trace = new ArrayList<>();
        List<TurnEvent> progress = new ArrayList<>();
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                (observation, cancellation) -> new CallDiagramTool(
                        new CreateDraftRequest(graph(
                                "<mxCell id=\"node-a\" value=\"Start\" "
                                        + "vertex=\"1\" parent=\"1\"/>"))),
                new DefaultDiagramAgentToolAdapter(store),
                store,
                approvingReview(),
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active(),
                DiagramAgentBudget.defaults(),
                trace::add);
        AgenticPlainGenerationAdapter adapter = new AgenticPlainGenerationAdapter(
                (ownerKey, selection) -> DiagramSkillBundle.empty(),
                runtime,
                trace::add);

        adapter.generate(
                request(PlainDrawAction.CREATE, ""),
                progress::add,
                () -> false);

        assertThat(trace)
                .extracting(PlainAgentTraceEvent::type)
                .containsSubsequence(
                        PlainAgentTraceType.SKILL_LOADING_STARTED,
                        PlainAgentTraceType.SKILLS_LOADED,
                        PlainAgentTraceType.AGENT_STARTED,
                        PlainAgentTraceType.DECISION_STARTED,
                        PlainAgentTraceType.DECISION_SELECTED);
        assertThat(progress)
                .filteredOn(event -> event.type().equals("plain_agent_candidate_submitted"))
                .hasSize(1);
    }

    @Test
    void delegatesTheCreatedDraftToVisualReviewAndSubmitsWithoutAnotherDrawDecision() {
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
        List<PlainAgentTraceEvent> trace = new ArrayList<>();
        List<TurnEvent> progress = new ArrayList<>();
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                decision,
                new DefaultDiagramAgentToolAdapter(store),
                store,
                approvingReview(),
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active(),
                DiagramAgentBudget.defaults(),
                trace::add);

        var result = runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false,
                progress::add);

        assertThat(decisions.get()).isEqualTo(1);
        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(result.mutationCount()).isEqualTo(1);
        assertThat(result.canvasXml()).contains("node-a");
        assertThat(trace)
                .filteredOn(event -> event.type() == PlainAgentTraceType.TOOL_COMPLETED)
                .extracting(PlainAgentTraceEvent::toolName)
                .containsExactly("create_draft");
        assertThat(trace)
                .filteredOn(event ->
                        event.type() == PlainAgentTraceType.VISUAL_REVIEW_COMPLETED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.toolName()).isEqualTo("visual_review_agent");
                    assertThat(event.outcomeCode()).isEqualTo("APPROVE");
                });
        assertThat(progress)
                .extracting(TurnEvent::type)
                .containsSubsequence(
                        "plain_agent_started",
                        "plain_agent_decision_started",
                        "plain_agent_decision_completed",
                        "plain_agent_tool_started",
                        "plain_agent_tool_completed",
                        "plain_agent_draft_preview",
                        "plain_agent_visual_review_started",
                        "plain_agent_visual_review_completed",
                        "plain_agent_candidate_submitted");
        assertThat(progress)
                .filteredOn(event -> event.type().equals("plain_agent_draft_preview"))
                .singleElement()
                .extracting(TurnEvent::payload)
                .asString()
                .contains("node-a");
        // The working copy is deleted after the XML has been captured for the outer commit seam.
        assertThatThrownBy(() -> store.read(attempt(), result.draftRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DRAFT_ATTEMPT_NOT_FOUND");
    }

    @Test
    void submitsTheDraftWithoutServerReviewWhenDrawioMustExportTheReviewPng() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        AtomicInteger reviews = new AtomicInteger();
        List<TurnEvent> progress = new ArrayList<>();
        DiagramDraftVisualReviewPort clientRenderedReview = new DiagramDraftVisualReviewPort() {
            @Override
            public DiagramDraftVisualReview review(PlainDrawPlan plan, DiagramDraftSnapshot draft) {
                reviews.incrementAndGet();
                return DiagramDraftVisualReview.unavailable(draft.digest(), "unexpected_server_review");
            }

            @Override
            public boolean defersToClientRenderedEvidence() {
                return true;
            }
        };
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                (observation, cancellation) -> new CallDiagramTool(
                        new CreateDraftRequest(graph(
                                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\"/>"))),
                new DefaultDiagramAgentToolAdapter(store),
                store,
                clientRenderedReview);

        var result = runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false,
                progress::add);

        assertThat(reviews.get()).isZero();
        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(result.canvasXml()).contains("node-a");
        assertThat(progress)
                .extracting(TurnEvent::type)
                .contains("plain_agent_draft_preview", "plain_agent_candidate_submitted")
                .doesNotContain(
                        "plain_agent_visual_review_started",
                        "plain_agent_visual_review_completed");
    }

    @Test
    void neverAutoSubmitsWhenTheModelRepeatsAnAction() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        DiagramAgentAction repeated = new CallDiagramTool(new CreateDraftRequest(graph(
                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\"/>")));
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                (observation, cancellation) -> repeated,
                new DefaultDiagramAgentToolAdapter(store),
                store,
                repairingReview("node-a"));

        assertThatThrownBy(() -> runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAIN_AGENT_REPEATED_ACTION");
    }

    @Test
    void injectsRepairReviewIntoTheNextDrawDecisionThenReviewsThePatch() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        AtomicInteger reviews = new AtomicInteger();
        DiagramDraftVisualReviewPort visualReviews = (plan, draft) -> {
            String decision = reviews.incrementAndGet() == 1 ? "REPAIR" : "APPROVE";
            return new DiagramDraftVisualReview(
                    draft.digest(),
                    decision,
                    true,
                    decision,
                    "REPAIR".equals(decision)
                            ? List.of(new DiagramDraftVisualIssue(
                            "LAYOUT_HIERARCHY",
                            "MAJOR",
                            List.of("node-a"),
                            "The node needs a local move.",
                            "Replace node-a with corrected geometry."))
                            : List.of(),
                    "",
                    "test-reviewer");
        };
        DiagramAgentDecisionPort decision = (observation, cancellation) -> {
            var state = observation.state();
            if (state.activeDraft() == null) {
                return new CallDiagramTool(new CreateDraftRequest(graph(
                        "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                                + "as=\"geometry\"/></mxCell>")));
            }
            if (state.latestVisualReview().requestsRepair()) {
                assertThat(state.latestToolResult().toolName()).isEqualTo("inspect_draft");
                assertThat(state.latestToolResult().cells())
                        .singleElement()
                        .satisfies(cell -> {
                            assertThat(cell.id()).isEqualTo("node-a");
                            assertThat(cell.rawXml()).contains(
                                    "<mxCell id=\"node-a\"",
                                    "<mxGeometry",
                                    "x=\"10\"");
                        });
                return new CallDiagramTool(new PatchDraftRequest(
                        state.activeDraft().ref(),
                        state.activeDraft().digest(),
                        List.of(DraftCellMutation.replace(
                                "node-a",
                                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                                        + "<mxGeometry x=\"20\" y=\"10\" width=\"80\" height=\"40\" "
                                        + "as=\"geometry\"/></mxCell>"))));
            }
            return new SubmitDiagramCandidate(
                    state.activeDraft().ref(), state.activeDraft().digest(), "Reviewed and ready.");
        };
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                decision,
                new DefaultDiagramAgentToolAdapter(store),
                store,
                visualReviews);

        List<TurnEvent> progress = new ArrayList<>();
        var result = runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false,
                progress::add);

        assertThat(reviews.get()).isEqualTo(2);
        assertThat(result.stepCount()).isEqualTo(2);
        assertThat(result.mutationCount()).isEqualTo(2);
        assertThat(result.canvasXml()).contains("x=\"20\"");
        assertThat(progress)
                .filteredOn(event -> event.type().equals("plain_agent_visual_review_completed")
                        && event.payload().contains("\tREPAIR\t"))
                .singleElement()
                .satisfies(event -> {
                    String[] fields = event.payload().split("\\t", -1);
                    assertThat(fields).hasSize(8);
                    assertThat(new String(
                            Base64.getUrlDecoder().decode(fields[6]),
                            StandardCharsets.UTF_8))
                            .isEqualTo("REPAIR");
                    assertThat(new String(
                            Base64.getUrlDecoder().decode(fields[7]),
                            StandardCharsets.UTF_8))
                            .contains("The node needs a local move.")
                            .contains("Replace node-a with corrected geometry.");
                });
    }

    @Test
    void rejectsVisualRepairOutsideTheGroundedTargetCells() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        DiagramDraftVisualReviewPort visualReviews = (plan, draft) ->
                new DiagramDraftVisualReview(
                        draft.digest(),
                        "REPAIR",
                        true,
                        "Only node-a needs a local repair.",
                        List.of(new DiagramDraftVisualIssue(
                                "LAYOUT_HIERARCHY",
                                "MAJOR",
                                List.of("node-a"),
                                "node-a is misplaced.",
                                "Replace node-a with corrected geometry.")),
                        "",
                        "test-reviewer");
        DiagramAgentDecisionPort decision = (observation, cancellation) -> {
            var state = observation.state();
            if (state.activeDraft() == null) {
                return new CallDiagramTool(new CreateDraftRequest(graph(
                        "<mxCell id=\"node-a\" value=\"A\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                                + "as=\"geometry\"/></mxCell>"
                                + "<mxCell id=\"node-b\" value=\"B\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"120\" y=\"10\" width=\"80\" height=\"40\" "
                                + "as=\"geometry\"/></mxCell>")));
            }
            return new CallDiagramTool(new PatchDraftRequest(
                    state.activeDraft().ref(),
                    state.activeDraft().digest(),
                    List.of(DraftCellMutation.replace(
                            "node-b",
                            "<mxCell id=\"node-b\" value=\"B\" vertex=\"1\" parent=\"1\">"
                                    + "<mxGeometry x=\"160\" y=\"10\" width=\"80\" height=\"40\" "
                                    + "as=\"geometry\"/></mxCell>"))));
        };
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                decision,
                new DefaultDiagramAgentToolAdapter(store),
                store,
                visualReviews);

        assertThatThrownBy(() -> runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAIN_AGENT_REPAIR_TARGET_NOT_ALLOWED");
    }

    @Test
    void submitsTheCurrentDraftWhenVisualReviewNeedsHumanInsteadOfContinuingTheLoop() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        AtomicInteger decisions = new AtomicInteger();
        DiagramDraftVisualReviewPort visualReviews = (plan, draft) ->
                new DiagramDraftVisualReview(
                        draft.digest(),
                        "NEEDS_HUMAN_REVIEW",
                        true,
                        "Some issues require human confirmation.",
                        List.of(),
                        "mixed_target_capabilities",
                        "test-reviewer");
        DiagramAgentDecisionPort decision = (observation, cancellation) -> {
            decisions.incrementAndGet();
            var state = observation.state();
            if (state.activeDraft() == null) {
                return new CallDiagramTool(new CreateDraftRequest(graph(
                        "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                                + "as=\"geometry\"/></mxCell>")));
            }
            throw new IllegalStateException("DECISION_SHOULD_NOT_CONTINUE");
        };
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                decision,
                new DefaultDiagramAgentToolAdapter(store),
                store,
                visualReviews);

        var result = runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false);

        assertThat(decisions.get()).isEqualTo(1);
        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(result.canvasXml()).contains("node-a");
        assertThat(result.assistantMessage()).contains("human review");
    }

    @Test
    void requiresHumanReviewWhenTheRequestedRepairCellCannotBeInspected() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        AtomicInteger decisions = new AtomicInteger();
        DiagramAgentDecisionPort decision = (observation, cancellation) -> {
            decisions.incrementAndGet();
            if (observation.state().activeDraft() == null) {
                return new CallDiagramTool(new CreateDraftRequest(graph(
                        "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                                + "as=\"geometry\"/></mxCell>")));
            }
            throw new IllegalStateException("DECISION_SHOULD_NOT_CONTINUE");
        };
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                decision,
                new DefaultDiagramAgentToolAdapter(store),
                store,
                repairingReview("missing-node"));

        var result = runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false);

        // Never give the model a repair turn without the exact authorized cell XML.
        assertThat(decisions.get()).isEqualTo(1);
        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(result.canvasXml()).contains("node-a");
        assertThat(result.assistantMessage()).contains("human review");
    }

    @Test
    void returnsTheLatestStructurallyValidDraftWhenStepBudgetIsExhausted() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        List<PlainAgentTraceEvent> trace = new ArrayList<>();
        String currentCanvas = graph(
                "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                        + "as=\"geometry\"/></mxCell>");
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                (observation, cancellation) -> new CallDiagramTool(
                        new InspectDraftRequest(
                                observation.state().activeDraft().ref(),
                                DraftInspectionScope.SUMMARY,
                                List.of(),
                                "")),
                new DefaultDiagramAgentToolAdapter(store),
                store,
                DiagramDraftVisualReviewPort.UNAVAILABLE,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active(),
                new DiagramAgentBudget(1, 3, 1, 1, 2, 1, 2),
                trace::add);

        var result = runtime.run(
                request(PlainDrawAction.EDIT, currentCanvas),
                DiagramSkillBundle.empty(),
                () -> false);

        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(result.canvasXml()).contains("node-a");
        assertThat(result.assistantMessage()).contains("step budget was exhausted");
        assertThat(trace)
                .filteredOn(event -> event.type() == PlainAgentTraceType.CANDIDATE_SUBMITTED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actionType()).isEqualTo("BUDGET_FALLBACK");
                    assertThat(event.outcomeCode())
                            .isEqualTo("CANDIDATE_SUBMITTED");
                });
        assertThat(trace)
                .filteredOn(event -> event.type() == PlainAgentTraceType.AGENT_STOPPED)
                .singleElement()
                .extracting(PlainAgentTraceEvent::outcomeCode)
                .isEqualTo("CANDIDATE_SUBMITTED");
    }

    @Test
    void neverCopiesArbitraryFailureTextIntoTheAgentTrace() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        List<PlainAgentTraceEvent> trace = new ArrayList<>();
        BoundedDiagramAgentRuntime runtime = new BoundedDiagramAgentRuntime(
                (observation, cancellation) -> {
                    throw new IllegalStateException("unsafe <mxGraphModel> model output");
                },
                new DefaultDiagramAgentToolAdapter(store),
                store,
                DiagramDraftVisualReviewPort.UNAVAILABLE,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active(),
                DiagramAgentBudget.defaults(),
                trace::add);

        assertThatThrownBy(() -> runtime.run(
                request(PlainDrawAction.CREATE, ""),
                DiagramSkillBundle.empty(),
                () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mxGraphModel");

        assertThat(trace)
                .filteredOn(event -> event.type() == PlainAgentTraceType.AGENT_STOPPED)
                .singleElement()
                .extracting(PlainAgentTraceEvent::outcomeCode)
                .isEqualTo("PLAIN_AGENT_RUNTIME_FAILED");
    }

    private DiagramDraftVisualReviewPort approvingReview() {
        return (plan, draft) -> new DiagramDraftVisualReview(
                draft.digest(),
                "APPROVE",
                true,
                "The draft is readable.",
                List.of(),
                "",
                "test-reviewer");
    }

    private DiagramDraftVisualReviewPort repairingReview(String targetCellId) {
        return (plan, draft) -> new DiagramDraftVisualReview(
                draft.digest(),
                "REPAIR",
                true,
                "The draft needs one local repair.",
                List.of(new DiagramDraftVisualIssue(
                        "LAYOUT_HIERARCHY",
                        "MAJOR",
                        List.of(targetCellId),
                        "The target is misplaced.",
                        "Replace the target with corrected geometry.")),
                "",
                "test-reviewer");
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
