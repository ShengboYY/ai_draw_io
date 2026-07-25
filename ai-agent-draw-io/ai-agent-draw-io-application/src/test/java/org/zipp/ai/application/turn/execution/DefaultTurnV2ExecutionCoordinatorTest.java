package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.PlainGenerationResult;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultTurnV2ExecutionCoordinatorTest {

    @Test
    void dispatchesOnlyPlainReadyRoutesToThePlainHandler() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        BaseTurnContext context = context(command);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());
        AtomicInteger generations = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        PlainDrawingHandler plain = new PlainDrawingHandler(
                (request, events) -> {
                    generations.incrementAndGet();
                    return new PlainGenerationResult("payload-1", "<mxGraphModel/>", "created");
                },
                commit -> {
                    commits.incrementAndGet();
                    return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                            TurnStatus.COMPLETED, "COMPLETED", "plain", commit.payloadRef(), "{}"));
                },
                new org.zipp.ai.application.turn.PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        TurnV2ExecutionOutcome outcome = new DefaultTurnV2ExecutionCoordinator(
                (ignoredAttempt, ignoredCommand) -> ready(
                        attempt, context, readSet, plainDecision(readSet, attempt), checkpoint(readSet, attempt)),
                plain).execute(attempt, command, ignoredEvents());

        TurnV2ExecutionOutcome.Committed committed = assertInstanceOf(
                TurnV2ExecutionOutcome.Committed.class, outcome);
        assertInstanceOf(FencedCommitOutcome.Committed.class, committed.outcome());
        assertEquals(1, generations.get());
        assertEquals(1, commits.get());
    }

    @Test
    void leavesUnsupportedRoutesUndispatchedWithoutCallingPlain() {
        UserTurnCommand command = command("do something unsupported");
        FencedAttempt attempt = attempt(command);
        BaseTurnContext context = context(command);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());
        AtomicInteger generations = new AtomicInteger();
        PlainDrawingHandler plain = new PlainDrawingHandler(
                (request, events) -> {
                    generations.incrementAndGet();
                    return new PlainGenerationResult("unexpected", "<mxGraphModel/>", "unexpected");
                },
                commit -> new FencedCommitOutcome.Rejected("unexpected"),
                new org.zipp.ai.application.turn.PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        TurnV2ExecutionOutcome.NotDispatched notDispatched = assertInstanceOf(
                TurnV2ExecutionOutcome.NotDispatched.class,
                new DefaultTurnV2ExecutionCoordinator(
                        (ignoredAttempt, ignoredCommand) -> ready(
                                attempt, context, readSet, unsupportedDecision(readSet, attempt),
                                checkpoint(readSet, attempt)),
                        plain).execute(attempt, command, ignoredEvents()));

        assertEquals("UNSUPPORTED_ROUTE_NOT_EXECUTABLE", notDispatched.code());
        assertEquals(0, generations.get());
    }

    @Test
    void preservesPreparationFenceLossWithoutInventingACommit() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        AtomicInteger generations = new AtomicInteger();
        PlainDrawingHandler plain = new PlainDrawingHandler(
                (request, events) -> {
                    generations.incrementAndGet();
                    return new PlainGenerationResult("unexpected", "<mxGraphModel/>", "unexpected");
                },
                commit -> new FencedCommitOutcome.Rejected("unexpected"),
                new org.zipp.ai.application.turn.PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());

        TurnV2ExecutionOutcome.PreparationBlocked blocked = assertInstanceOf(
                TurnV2ExecutionOutcome.PreparationBlocked.class,
                new DefaultTurnV2ExecutionCoordinator(
                        (ignoredAttempt, ignoredCommand) -> new TurnV2PreHandlerOutcome.FenceLost(
                                new TurnStatusRef(attempt.key())),
                        plain).execute(attempt, command, ignoredEvents()));

        assertInstanceOf(TurnV2PreHandlerOutcome.FenceLost.class, blocked.outcome());
        assertEquals(0, generations.get());
    }

    private static TurnV2PreHandlerOutcome.Ready ready(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            TurnRouteDecision decision,
            TurnDecisionCheckpoint checkpoint
    ) {
        return new TurnV2PreHandlerOutcome.Ready(attempt, context, readSet, decision, checkpoint);
    }

    private static TurnRouteDecision plainDecision(ContextReadSet readSet, FencedAttempt attempt) {
        return new TurnRouteDecision.Plain(new PrePlanOutcome.SourceFreeReady(
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow"),
                new PlanningLineageFingerprint("a".repeat(64)),
                readSet.digest(),
                attempt.inputBindingDigest()));
    }

    private static TurnRouteDecision unsupportedDecision(ContextReadSet readSet, FencedAttempt attempt) {
        return new TurnRouteDecision.Unsupported(new PrePlanOutcome.Unsupported(
                "UNSUPPORTED_ACTION",
                new PlanningLineageFingerprint("b".repeat(64)),
                readSet.digest(),
                attempt.inputBindingDigest()));
    }

    private static TurnDecisionCheckpoint checkpoint(ContextReadSet readSet, FencedAttempt attempt) {
        return TurnDecisionCheckpoint.create(
                1, readSet.digest(), attempt.inputBindingDigest(), "ROUTE", "{}");
    }

    private static UserTurnCommand command(String content) {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", content, "runtime-1",
                TurnDeclarations.empty());
    }

    private static FencedAttempt attempt(UserTurnCommand command) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", command.turnId()),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                17,
                TurnInputBindingDigestCalculator.current(command),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static BaseTurnContext context(UserTurnCommand command) {
        return new BaseTurnContext(
                new CurrentRequestContext(command.turnId(), command.diagramId(),
                        new CurrentInstruction(command.content())),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new AbsentContext<>("not needed"),
                new ContextDiagnostics(List.of()));
    }

    private static ContextReadSet readSet(long highWater) {
        return ContextReadSet.create(
                1,
                highWater,
                ContextSlicePin.pinned(ContextSlice.SUMMARY, "summary-v1", 1, "a".repeat(64)),
                ContextSlicePin.pinned(ContextSlice.MEMBERSHIP, "membership-v1", 1, "b".repeat(64)),
                ContextSlicePin.pinned(ContextSlice.PROFILE, "profile-v1", 1, "c".repeat(64)),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private static TurnEventSink ignoredEvents() {
        return event -> { };
    }
}
