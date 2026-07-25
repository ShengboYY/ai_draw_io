package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    return new PlainGenerationResult("payload-1");
                },
                command -> {
                    committedPayloads.add(command.payloadRef());
                    return new FencedCommitOutcome.Committed(
                            new PersistedTurnOutcome(TurnStatus.COMPLETED, "COMPLETED",
                                    "plain", command.payloadRef(), "{}"));
                },
                new PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        FencedCommitOutcome outcome = handler.execute(
                attempt,
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
                new PlainDrawPlan(PlainDrawAction.EDIT, "edit the flow"),
                event -> { }));
        assertEquals(0, commits[0]);
    }

    @Test
    void runtimeRegistryHasNoDynamicSourceCapability() {
        PlainRuntimeRegistry registry = new PlainRuntimeRegistry();

        assertEquals(true, registry.isSourceFree());
        assertEquals(List.of(PlainRuntimeCapability.PLAIN_GENERATION),
                List.copyOf(registry.capabilities()));
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
}
