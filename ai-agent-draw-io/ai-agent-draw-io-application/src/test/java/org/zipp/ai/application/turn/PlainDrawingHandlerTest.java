package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlainDrawingHandlerTest {

    @Test
    void sourceFreeCreateUsesOnlyPlainGenerationAndOneFencedCommit() {
        FencedAttempt attempt = attempt();
        List<String> committedPayloads = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PlainDrawingHandler handler = new PlainDrawingHandler(
                (request, sink) -> {
                    assertEquals(PlainDrawAction.CREATE, request.plan().action());
                    assertEquals("m2-plain-source-free", request.profile().id());
                    assertEquals("diagram-1", request.context().request().diagramId());
                    assertEquals(readSet(), request.readSet());
                    return new PlainGenerationResult("payload-1", "<mxGraphModel/>", "created");
                },
                command -> {
                    committedPayloads.add(command.payloadRef());
                    assertEquals(PlainDrawAction.CREATE, command.action());
                    assertEquals("diagram-1", command.diagramId());
                    assertEquals(0, command.expectedCanvasVersion());
                    return new FencedCommitOutcome.Committed(
                            new PersistedTurnOutcome(TurnStatus.COMPLETED, "COMPLETED",
                                    "plain", command.payloadRef(), "{}"));
                },
                new PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        FencedCommitOutcome outcome = handler.execute(
                attempt,
                context(),
                readSet(),
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a login flow"),
                event -> events.add(event.type()));

        assertEquals(List.of("payload-1"), committedPayloads);
        assertEquals(List.of("plain_started", "plain_committed"), events);
        assertEquals(TurnStatus.COMPLETED,
                ((FencedCommitOutcome.Committed) outcome).outcome().status());
    }

    @Test
    void generationFailureCannotReachStrongCommit() {
        int[] commits = {0};
        PlainDrawingHandler handler = new PlainDrawingHandler(
                (request, events) -> { throw new IllegalStateException("generation unavailable"); },
                command -> {
                    commits[0]++;
                    return new FencedCommitOutcome.Rejected("unexpected");
                },
                new PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        assertThrows(IllegalStateException.class, () -> handler.execute(
                attempt(),
                context(),
                readSet(),
                new PlainDrawPlan(PlainDrawAction.EDIT, "edit the flow"),
                event -> { }));
        assertEquals(0, commits[0]);
    }

    @Test
    void disabledAttemptCannotReachStrongCommit() {
        AttemptWriteGate gate = new AttemptWriteGate();
        FencedAttempt attempt = attempt();
        gate.disableAndDrain(attempt);
        int[] commits = {0};

        PlainDrawingHandler handler = new PlainDrawingHandler(
                (request, events) -> new PlainGenerationResult("payload-1", "<mxGraphModel/>", "created"),
                command -> {
                    commits[0]++;
                    return new FencedCommitOutcome.Rejected("unexpected");
                },
                new PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree(),
                gate);

        FencedCommitOutcome outcome = handler.execute(
                attempt,
                context(),
                readSet(),
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a login flow"),
                event -> { });

        assertInstanceOf(FencedCommitOutcome.Rejected.class, outcome);
        assertEquals("TURN_WRITE_GATE_DISABLED", ((FencedCommitOutcome.Rejected) outcome).code());
        assertEquals(0, commits[0]);
    }

    @Test
    void runtimeRegistryHasNoDynamicSourceCapability() {
        PlainRuntimeRegistry registry = new PlainRuntimeRegistry();

        assertEquals(true, registry.isSourceFree());
        assertEquals(List.of(PlainRuntimeCapability.PLAIN_GENERATION),
                List.copyOf(registry.capabilities()));
    }

    @Test
    void legacyOrDynamicProfileCannotEnterTheSourceFreeDrawingHandler() {
        assertThrows(IllegalArgumentException.class, () -> new PlainDrawingHandler(
                (request, events) -> new PlainGenerationResult("payload-1", "<mxGraphModel/>", "created"),
                command -> new FencedCommitOutcome.Rejected("unexpected"),
                new PlainRuntimeRegistry(),
                new PlainExecutionProfile("legacy-profile")));
    }

    @ParameterizedTest
    @EnumSource(PlainDrawAction.class)
    void everyPlainDrawingActionUsesTheSameSourceFreeCommitSeam(PlainDrawAction action) {
        int[] commits = {0};
        PlainDrawingHandler handler = new PlainDrawingHandler(
                (request, events) -> new PlainGenerationResult("payload-1", "<mxGraphModel/>", "created"),
                command -> {
                    commits[0]++;
                    assertEquals(action, command.action());
                    return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                            TurnStatus.COMPLETED, "COMPLETED", "plain", command.payloadRef(), "{}"));
                },
                new PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        FencedCommitOutcome outcome = handler.execute(
                attempt(), context(), readSet(), new PlainDrawPlan(action, "draw the flow"), event -> { });

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
        assertEquals(1, commits[0]);
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                AttemptLease.fromDatabaseClock(
                        "attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private BaseTurnContext context() {
        return new BaseTurnContext(
                new CurrentRequestContext("turn-1", "diagram-1", new CurrentInstruction("draw")),
                new AvailableContext<>(new CurrentMessageAttachmentsContext("binding-1", List.of()), "attachments"),
                new AbsentContext<>("no clarification"),
                new AvailableContext<>(new TrustedCanvasContext(false, 0, 0, ""), "canvas"),
                new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(new ConversationContext(List.of(), ""), "conversation"),
                new AbsentContext<>("no membership"),
                new AbsentContext<>("no profile"),
                new AbsentContext<>("no memory"),
                new ContextDiagnostics(List.of()));
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
}
