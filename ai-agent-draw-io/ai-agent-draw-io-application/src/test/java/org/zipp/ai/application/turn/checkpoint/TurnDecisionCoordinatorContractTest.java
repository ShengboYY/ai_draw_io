package org.zipp.ai.application.turn.checkpoint;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnLifecycleTraceEvent;
import org.zipp.ai.application.turn.TurnLifecycleTraceType;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteComputationOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteComputer;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnDecisionCoordinatorContractTest {

    private static final String INPUT_DIGEST = "b".repeat(64);
    @Test
    void foundCheckpointSkipsRouteComputation() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        ContextReadSet readSet = readSet(17);
        TurnRouteDecision winner = decision(readSet.digest(), attempt.inputBindingDigest());
        FakeCodec codec = new FakeCodec();
        TurnDecisionCheckpoint checkpoint = checkpoint(codec, winner, readSet.digest(), attempt);
        AtomicInteger computations = new AtomicInteger();
        TurnDecisionCoordinator coordinator = coordinator(
                ignored -> new TurnDecisionCheckpointLoadOutcome.Found(checkpoint),
                (ignored, proposal) -> {
                    throw new AssertionError("Found checkpoint must not commit");
                },
                (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) -> {
                    computations.incrementAndGet();
                    return new TurnRouteComputationOutcome.Ready(winner);
                },
                codec);

        TurnDecisionPreparationOutcome outcome = coordinator.preparePinned(
                attempt, command, context(command), readSet);

        assertEquals(winner, assertInstanceOf(TurnDecisionPreparationOutcome.Ready.class, outcome).decision());
        assertEquals(0, computations.get());
        assertEquals(1, codec.decodes.get());
    }

    @Test
    void missingCheckpointComputesAndPinsTheRoute() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        ContextReadSet readSet = readSet(17);
        TurnRouteDecision computed = decision(readSet.digest(), attempt.inputBindingDigest());
        AtomicInteger computations = new AtomicInteger();
        FakeCodec codec = new FakeCodec();
        List<TurnLifecycleTraceEvent> traces = new ArrayList<>();
        TurnDecisionCoordinator coordinator = new DefaultTurnDecisionCoordinator(
                ignored -> new TurnDecisionCheckpointLoadOutcome.Missing(),
                (ignored, proposal) -> new TurnDecisionCheckpointOutcome.Pinned(proposal.value()),
                (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) -> {
                    computations.incrementAndGet();
                    return new TurnRouteComputationOutcome.Ready(computed);
                },
                codec,
                traces::add);

        TurnDecisionPreparationOutcome outcome = coordinator.preparePinned(
                attempt, command, context(command), readSet);

        TurnDecisionPreparationOutcome.Ready ready =
                assertInstanceOf(TurnDecisionPreparationOutcome.Ready.class, outcome);
        assertEquals(computed, ready.decision());
        assertEquals(readSet.digest(), ready.checkpoint().contextReadSetDigest());
        assertEquals(1, computations.get());
        TurnLifecycleTraceEvent trace = traces.stream()
                .filter(event -> event.type() == TurnLifecycleTraceType.DECISION_CHECKPOINT)
                .findFirst()
                .orElseThrow();
        assertEquals("PINNED", trace.outcomeCode());
        assertEquals(ready.checkpoint().digest(), trace.decisionDigest());
        assertEquals(attempt.inputBindingDigest(), trace.inputBindingDigest());
    }

    @Test
    void casRetryReloadsPersistedWinnerWithoutRecomputing() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        ContextReadSet readSet = readSet(17);
        TurnRouteDecision winner = decision(readSet.digest(), attempt.inputBindingDigest());
        FakeCodec codec = new FakeCodec();
        TurnDecisionCheckpoint checkpoint = checkpoint(codec, winner, readSet.digest(), attempt);
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger computations = new AtomicInteger();
        TurnDecisionCoordinator coordinator = coordinator(
                ignored -> loads.getAndIncrement() == 0
                        ? new TurnDecisionCheckpointLoadOutcome.Missing()
                        : new TurnDecisionCheckpointLoadOutcome.Found(checkpoint),
                (ignored, proposal) -> new TurnDecisionCheckpointOutcome.Retry(),
                (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) -> {
                    computations.incrementAndGet();
                    return new TurnRouteComputationOutcome.Ready(decision(
                            readSet.digest(), attempt.inputBindingDigest()));
                },
                codec);

        TurnDecisionPreparationOutcome outcome = coordinator.preparePinned(
                attempt, command, context(command), readSet);

        assertEquals(winner, assertInstanceOf(TurnDecisionPreparationOutcome.Ready.class, outcome).decision());
        assertEquals(2, loads.get());
        assertEquals(1, computations.get());
    }

    @Test
    void mismatchedContextDigestFailsClosedBeforeComputation() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        ContextReadSet readSet = readSet(17);
        FakeCodec codec = new FakeCodec();
        TurnRouteDecision winner = decision(readSet.digest(), attempt.inputBindingDigest());
        TurnDecisionCheckpoint checkpoint = checkpoint(codec, winner, "f".repeat(64), attempt);
        AtomicInteger computations = new AtomicInteger();
        TurnDecisionCoordinator coordinator = coordinator(
                ignored -> new TurnDecisionCheckpointLoadOutcome.Found(checkpoint),
                (ignored, proposal) -> new TurnDecisionCheckpointOutcome.Retry(),
                (ignoredAttempt, ignoredCommand, ignoredContext, ignoredReadSet) -> {
                    computations.incrementAndGet();
                    return new TurnRouteComputationOutcome.Ready(winner);
                },
                codec);

        TurnDecisionPreparationOutcome.Unavailable unavailable = assertInstanceOf(
                TurnDecisionPreparationOutcome.Unavailable.class,
                coordinator.preparePinned(attempt, command, context(command), readSet));

        assertEquals("TERMINAL_UNAVAILABLE", unavailable.code().name());
        assertTrue(computations.get() == 0);
    }

    private TurnDecisionCoordinator coordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit,
            TurnRouteComputer routeComputer,
            TurnRouteDecisionCodec codec
    ) {
        return new DefaultTurnDecisionCoordinator(query, commit, routeComputer, codec);
    }

    private TurnDecisionCheckpoint checkpoint(
            FakeCodec codec,
            TurnRouteDecision decision,
            String contextDigest,
            FencedAttempt attempt
    ) {
        EncodedTurnRouteDecision encoded = codec.encode(decision);
        return TurnDecisionCheckpoint.create(
                1, contextDigest, attempt.inputBindingDigest(), encoded.kind(), encoded.json());
    }

    private TurnRouteDecision decision(String contextDigest, String inputDigest) {
        return new TurnRouteDecision.Plain(new PrePlanOutcome.SourceFreeReady(
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw"),
                new PlanningLineageFingerprint("c".repeat(64)),
                contextDigest,
                inputDigest));
    }

    private UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw", "session-1",
                TurnDeclarations.empty());
    }

    private FencedAttempt attempt(UserTurnCommand command) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1,
                        Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                17,
                TurnInputBindingDigestCalculator.current(command),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private BaseTurnContext context(UserTurnCommand command) {
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

    private ContextReadSet readSet(long highWater) {
        return ContextReadSet.create(
                1,
                highWater,
                ContextSlicePin.pinned(ContextSlice.SUMMARY, "summary-v1", 1, "a".repeat(64)),
                ContextSlicePin.pinned(ContextSlice.MEMBERSHIP, "membership-v1", 1, "b".repeat(64)),
                ContextSlicePin.pinned(ContextSlice.PROFILE, "profile-v1", 1, "c".repeat(64)),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private static final class FakeCodec implements TurnRouteDecisionCodec {
        private final Map<String, TurnRouteDecision> values = new HashMap<>();
        private final AtomicInteger encodes = new AtomicInteger();
        private final AtomicInteger decodes = new AtomicInteger();

        @Override
        public EncodedTurnRouteDecision encode(TurnRouteDecision decision) {
            String json = "{\"id\":\"" + encodes.incrementAndGet() + "\"}";
            values.put(json, decision);
            return new EncodedTurnRouteDecision("TEST_ROUTE", json);
        }

        @Override
        public TurnRouteDecision decode(TurnDecisionCheckpoint checkpoint) {
            decodes.incrementAndGet();
            TurnRouteDecision value = values.get(checkpoint.decisionJson());
            if (value == null) {
                throw new IllegalArgumentException("unknown fake checkpoint payload");
            }
            return value;
        }
    }
}
