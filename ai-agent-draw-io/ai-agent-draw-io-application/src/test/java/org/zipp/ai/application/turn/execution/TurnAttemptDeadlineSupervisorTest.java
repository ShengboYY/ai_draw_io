package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnWriteGate;
import org.zipp.ai.application.turn.PersistedTurnOutcome;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnAttemptDeadlineSupervisorTest {

    @Test
    void closedWriteGatePreventsTheDeadlinePortCall() {
        AttemptWriteGate gate = new AttemptWriteGate();
        FencedAttempt attempt = attempt();
        gate.disableAndDrain(attempt);
        AtomicInteger calls = new AtomicInteger();
        TurnAttemptDeadlineSupervisor supervisor = new TurnAttemptDeadlineSupervisor(
                (ignoredAttempt, ignoredReason) -> {
                    calls.incrementAndGet();
                    return new DeadlineCancelOutcome.TransientFailure("unexpected");
                }, gate);

        TurnAttemptDeadlineOutcome.WriteGateDisabled outcome = assertInstanceOf(
                TurnAttemptDeadlineOutcome.WriteGateDisabled.class,
                supervisor.cancel(attempt, AttemptDeadlineReason.EXECUTION_DEADLINE));

        assertEquals(0, calls.get());
        assertInstanceOf(TurnAttemptDeadlineOutcome.WriteGateDisabled.class, outcome);
    }

    @Test
    void holdsThePermitAcrossTheDurableDeadlineCancellationCall() {
        RecordingGate gate = new RecordingGate();
        FencedAttempt attempt = attempt();
        AtomicBoolean portObservedPermit = new AtomicBoolean();
        TurnAttemptDeadlineSupervisor supervisor = new TurnAttemptDeadlineSupervisor(
                (ignoredAttempt, ignoredReason) -> {
                    portObservedPermit.set(gate.active.get() == 1);
                    return new DeadlineCancelOutcome.Cancelled(
                            new PersistedTurnOutcome(TurnStatus.CANCELLED,
                                    "EXECUTION_DEADLINE", "deadline", null, "{}"));
                }, gate);

        TurnAttemptDeadlineOutcome.Delegated outcome = assertInstanceOf(
                TurnAttemptDeadlineOutcome.Delegated.class,
                supervisor.cancel(attempt, AttemptDeadlineReason.EXECUTION_DEADLINE));

        assertInstanceOf(DeadlineCancelOutcome.Cancelled.class, outcome.outcome());
        assertTrue(portObservedPermit.get());
        assertEquals(0, gate.active.get());
    }

    @Test
    void delegatesStaleEpochDecisionToTheDurableFence() {
        AttemptWriteGate gate = new AttemptWriteGate();
        FencedAttempt attempt = attempt();
        TurnAttemptDeadlineSupervisor supervisor = new TurnAttemptDeadlineSupervisor(
                (ignoredAttempt, ignoredReason) -> new DeadlineCancelOutcome.FenceLost(status(attempt)), gate);

        TurnAttemptDeadlineOutcome.Delegated outcome = assertInstanceOf(
                TurnAttemptDeadlineOutcome.Delegated.class,
                supervisor.cancel(attempt, AttemptDeadlineReason.EXECUTION_DEADLINE));

        assertInstanceOf(DeadlineCancelOutcome.FenceLost.class, outcome.outcome());
    }

    private static TurnStatusView status(FencedAttempt attempt) {
        return new TurnStatusView(
                attempt.key(), TurnStatus.RUNNING, attempt.attemptId(), attempt.attemptEpoch(),
                null, null, Instant.now());
    }

    private static FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1,
                        Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private static final class RecordingGate implements TurnWriteGate {

        private final AtomicInteger active = new AtomicInteger();

        @Override
        public Optional<Permit> tryEnter(FencedAttempt attempt) {
            active.incrementAndGet();
            return Optional.of(() -> active.decrementAndGet());
        }

        @Override
        public void disableAndDrain(FencedAttempt attempt) {
        }
    }
}
