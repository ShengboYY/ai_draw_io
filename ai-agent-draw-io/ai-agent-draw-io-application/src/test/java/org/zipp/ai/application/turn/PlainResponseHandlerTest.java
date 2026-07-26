package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlainResponseHandlerTest {

    @Test
    void answerUsesResponseCommitWithoutCanvasMutation() {
        List<ResponseTurnCommit> commits = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PlainResponseHandler handler = new PlainResponseHandler(
                (request, sink) -> {
                    assertEquals(PlainResponseKind.ANSWER, request.plan().kind());
                    assertEquals("m2-plain-source-free", request.profile().id());
                    return new PlainResponseGenerationResult("answer", "response-1");
                },
                command -> {
                    commits.add(command);
                    return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                            TurnStatus.COMPLETED, "COMPLETED", "response", command.payloadRef(), "{}"));
                },
                PlainExecutionProfile.m2SourceFree());

        FencedCommitOutcome outcome = handler.execute(
                attempt(), context(), readSet(),
                new PlainResponsePlan(PlainResponseKind.ANSWER, "explain the diagram"),
                event -> events.add(event.type()));

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
        assertEquals(1, commits.size());
        assertEquals("diagram-1", commits.get(0).diagramId());
        assertEquals("answer", commits.get(0).assistantMessage());
        assertEquals(List.of("plain_response_started", "plain_response_committed"), events);
    }

    @Test
    void generationFailureCannotReachResponseCommit() {
        int[] commits = {0};
        PlainResponseHandler handler = new PlainResponseHandler(
                (request, events) -> { throw new IllegalStateException("generation unavailable"); },
                command -> {
                    commits[0]++;
                    return new FencedCommitOutcome.Rejected("unexpected");
                },
                PlainExecutionProfile.m2SourceFree());

        assertThrows(IllegalStateException.class, () -> handler.execute(
                attempt(), context(), readSet(),
                new PlainResponsePlan(PlainResponseKind.REVIEW, "review the diagram"),
                event -> { }));
        assertEquals(0, commits[0]);
    }

    @Test
    void disabledAttemptCannotReachResponseCommit() {
        AttemptWriteGate gate = new AttemptWriteGate();
        FencedAttempt attempt = attempt();
        gate.disableAndDrain(attempt);
        int[] commits = {0};
        PlainResponseHandler handler = new PlainResponseHandler(
                (request, events) -> new PlainResponseGenerationResult("answer", "response-1"),
                command -> {
                    commits[0]++;
                    return new FencedCommitOutcome.Rejected("unexpected");
                },
                PlainExecutionProfile.m2SourceFree(), gate);

        FencedCommitOutcome outcome = handler.execute(
                attempt, context(), readSet(),
                new PlainResponsePlan(PlainResponseKind.DIRECT_REPLY, "reply"), event -> { });

        assertEquals("TURN_WRITE_GATE_DISABLED", ((FencedCommitOutcome.Rejected) outcome).code());
        assertEquals(0, commits[0]);
    }

    @Test
    void legacyOrDynamicProfileCannotEnterTheSourceFreeResponseHandler() {
        assertThrows(IllegalArgumentException.class, () -> new PlainResponseHandler(
                (request, events) -> new PlainResponseGenerationResult("answer", "response-1"),
                command -> new FencedCommitOutcome.Rejected("unexpected"),
                new PlainExecutionProfile("legacy-profile")));
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
                new CurrentRequestContext("turn-1", "diagram-1", new CurrentInstruction("answer")),
                new AvailableContext<>(new CurrentMessageAttachmentsContext("binding-1", List.of()),
                        "attachments"),
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
