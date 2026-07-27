package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionPreparationOutcome;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextPreparationOutcome;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextAssemblyOutcome;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultTurnV2PreHandlerCoordinatorTest {

    @Test
    void passesThePinnedContextWinnerToTheCheckpointedDecision() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        BaseTurnContext context = context(command);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());
        TurnRouteDecision decision = plainDecision(readSet, attempt);
        TurnDecisionCheckpoint checkpoint = checkpoint(readSet, attempt);

        ContextAssemblyCoordinator assembly = assemblyReturning(
                new ContextPreparationOutcome.Ready(context, readSet));
        TurnDecisionCoordinator decisions = (actualAttempt, actualCommand, actualContext, actualReadSet) -> {
            assertSame(attempt, actualAttempt);
            assertSame(command, actualCommand);
            assertSame(context, actualContext);
            assertSame(readSet, actualReadSet);
            return new TurnDecisionPreparationOutcome.Ready(decision, checkpoint);
        };

        TurnV2PreHandlerOutcome.Ready ready = assertInstanceOf(
                TurnV2PreHandlerOutcome.Ready.class,
                new DefaultTurnV2PreHandlerCoordinator(assembly, decisions).prepare(attempt, command));

        assertSame(attempt, ready.attempt());
        assertSame(context, ready.context());
        assertSame(readSet, ready.readSet());
        assertSame(decision, ready.decision());
        assertSame(checkpoint, ready.checkpoint());
    }

    @Test
    void contextUnavailableStopsBeforeCallingTheDecisionCoordinator() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        AtomicBoolean decisionCalled = new AtomicBoolean();
        TurnStatusRef status = new TurnStatusRef(attempt.key());
        ContextAssemblyCoordinator assembly = assemblyReturning(
                new ContextPreparationOutcome.Unavailable(
                        status, TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ofSeconds(1)));
        TurnDecisionCoordinator decisions = (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) -> {
            decisionCalled.set(true);
            throw new AssertionError("decision must not run when Context is unavailable");
        };

        TurnV2PreHandlerOutcome.Unavailable unavailable = assertInstanceOf(
                TurnV2PreHandlerOutcome.Unavailable.class,
                new DefaultTurnV2PreHandlerCoordinator(assembly, decisions).prepare(attempt, command));

        assertSame(status, unavailable.status());
        assertEquals(TurnFailureCode.TERMINAL_UNAVAILABLE, unavailable.code());
        assertEquals(Duration.ofSeconds(1), unavailable.retryAfter());
        assertEquals(false, decisionCalled.get());
    }

    @Test
    void decisionFenceLossIsExposedWithoutDispatchingAHandler() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        BaseTurnContext context = context(command);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());
        TurnStatusRef status = new TurnStatusRef(attempt.key());
        ContextAssemblyCoordinator assembly = assemblyReturning(
                new ContextPreparationOutcome.Ready(context, readSet));
        TurnDecisionCoordinator decisions = (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) ->
                new TurnDecisionPreparationOutcome.FenceLost(status);

        TurnV2PreHandlerOutcome.FenceLost lost = assertInstanceOf(
                TurnV2PreHandlerOutcome.FenceLost.class,
                new DefaultTurnV2PreHandlerCoordinator(assembly, decisions).prepare(attempt, command));

        assertSame(status, lost.status());
    }

    @Test
    void durableTerminalStateStopsAfterTheDecisionPhaseWithoutReturningAReadyRoute() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        BaseTurnContext context = context(command);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());
        TurnRouteDecision decision = plainDecision(readSet, attempt);
        TurnDecisionCheckpoint checkpoint = checkpoint(readSet, attempt);
        AtomicInteger checks = new AtomicInteger();

        TurnAttemptExecutionStatePort state = ignored -> {
            if (checks.incrementAndGet() < 3) {
                return new TurnAttemptExecutionStatePort.StateOutcome.Active();
            }
            return new TurnAttemptExecutionStatePort.StateOutcome.AlreadyTerminal(
                    new PersistedTurnOutcome(TurnStatus.CANCELLED, "CANCELLED_BY_USER",
                            "cancel", null, "{}"));
        };
        ContextAssemblyCoordinator assembly = assemblyReturning(
                new ContextPreparationOutcome.Ready(context, readSet));
        TurnDecisionCoordinator decisions = (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) ->
                new TurnDecisionPreparationOutcome.Ready(decision, checkpoint);

        TurnV2PreHandlerOutcome.AlreadyTerminal terminal = assertInstanceOf(
                TurnV2PreHandlerOutcome.AlreadyTerminal.class,
                new DefaultTurnV2PreHandlerCoordinator(assembly, decisions, state)
                        .prepare(attempt, command));

        assertEquals(3, checks.get());
        assertEquals(TurnStatus.CANCELLED, terminal.outcome().status());
    }

    @Test
    void durableTerminalStateTakesPrecedenceOverAContextTerminalResult() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command);
        AtomicInteger checks = new AtomicInteger();
        TurnAttemptExecutionStatePort state = ignored -> {
            if (checks.incrementAndGet() < 2) {
                return new TurnAttemptExecutionStatePort.StateOutcome.Active();
            }
            return new TurnAttemptExecutionStatePort.StateOutcome.AlreadyTerminal(
                    new PersistedTurnOutcome(TurnStatus.CANCELLED, "CANCELLED_BY_USER",
                            "cancel", null, "{}"));
        };
        ContextAssemblyCoordinator assembly = assemblyReturning(
                new ContextPreparationOutcome.Terminal("CONTEXT_READ_SET_REVOKED", "revoked"));
        TurnDecisionCoordinator decisions = (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) -> {
            throw new AssertionError("decision must not run after a context terminal");
        };

        TurnV2PreHandlerOutcome.AlreadyTerminal terminal = assertInstanceOf(
                TurnV2PreHandlerOutcome.AlreadyTerminal.class,
                new DefaultTurnV2PreHandlerCoordinator(assembly, decisions, state)
                        .prepare(attempt, command));

        assertEquals(TurnStatus.CANCELLED, terminal.outcome().status());
        assertEquals(2, checks.get());
    }

    private static TurnRouteDecision plainDecision(ContextReadSet readSet, FencedAttempt attempt) {
        return new TurnRouteDecision.Plain(new PrePlanOutcome.SourceFreeReady(
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow"),
                new PlanningLineageFingerprint("a".repeat(64)),
                readSet.digest(),
                attempt.inputBindingDigest()));
    }

    private static ContextAssemblyCoordinator assemblyReturning(ContextPreparationOutcome outcome) {
        return new ContextAssemblyCoordinator() {
            @Override
            public ContextAssemblyOutcome assemble(FencedAttempt attempt, UserTurnCommand command) {
                throw new AssertionError("pre-handler coordinator must use prepareBeforeRouter");
            }

            @Override
            public ContextPreparationOutcome prepareBeforeRouter(
                    FencedAttempt attempt,
                    UserTurnCommand command
            ) {
                return outcome;
            }
        };
    }

    private static TurnDecisionCheckpoint checkpoint(ContextReadSet readSet, FencedAttempt attempt) {
        return TurnDecisionCheckpoint.create(
                1, readSet.digest(), attempt.inputBindingDigest(), "PLAIN", "{}");
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
                new ContextDiagnostics(java.util.List.of()));
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
}
