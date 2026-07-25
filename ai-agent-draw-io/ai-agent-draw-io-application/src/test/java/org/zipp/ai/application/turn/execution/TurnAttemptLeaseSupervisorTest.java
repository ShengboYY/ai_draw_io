package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnAttemptLeaseSupervisorTest {

    @Test
    void renewalRebuildsTheAttemptAndUsesTheHeartbeatMonotonicAnchor() {
        FencedAttempt current = attempt();
        AttemptLease renewedLease = AttemptLease.fromDatabaseClock(
                current.attemptId(), current.attemptEpoch(),
                Instant.parse("2026-07-26T00:00:10Z"),
                Instant.parse("2026-07-26T00:00:40Z"), 30_000);
        TurnAttemptLeaseSupervisor supervisor = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseRenewed(renewedLease),
                executor(new AtomicInteger()),
                () -> 456L);

        TurnAttemptHeartbeatOutcome.Renewed outcome = assertInstanceOf(
                TurnAttemptHeartbeatOutcome.Renewed.class, supervisor.heartbeat(current));

        assertEquals(renewedLease, outcome.attempt().lease());
        assertEquals(456L, outcome.timing().callStartedNanos());
        assertEquals(current.contextMessageHighWater(), outcome.attempt().contextMessageHighWater());
    }

    @Test
    void leaseFenceLossDrainsWritesBeforeReturningOwnershipLost() {
        FencedAttempt current = attempt();
        AtomicInteger drains = new AtomicInteger();
        TurnStatusView status = status(current, TurnStatus.RUNNING);
        TurnAttemptLeaseSupervisor supervisor = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseFenceLost(status),
                executor(drains));

        TurnAttemptHeartbeatOutcome.OwnershipLost outcome = assertInstanceOf(
                TurnAttemptHeartbeatOutcome.OwnershipLost.class, supervisor.heartbeat(current));

        assertEquals(current.key(), outcome.status().key());
        assertEquals(1, drains.get());
    }

    @Test
    void terminalAndUnavailableHeartbeatsDrainButTransientFailureCanRetry() {
        FencedAttempt current = attempt();
        AtomicInteger terminalDrains = new AtomicInteger();
        TurnStatusView terminalStatus = status(current, TurnStatus.COMPLETED);
        TurnAttemptLeaseSupervisor terminalSupervisor = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseAlreadyTerminal(
                        new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", "payload", "{}")),
                executor(terminalDrains));
        assertInstanceOf(TurnAttemptHeartbeatOutcome.PersistedTerminal.class,
                terminalSupervisor.heartbeat(current));
        assertEquals(1, terminalDrains.get());

        AtomicInteger unavailableDrains = new AtomicInteger();
        TurnAttemptLeaseSupervisor unavailableSupervisor = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseTerminalUnavailable(
                        terminalStatus, TurnFailureCode.TERMINAL_UNAVAILABLE,
                        Duration.ofSeconds(2)),
                executor(unavailableDrains));
        TurnAttemptHeartbeatOutcome.Unavailable unavailable = assertInstanceOf(
                TurnAttemptHeartbeatOutcome.Unavailable.class,
                unavailableSupervisor.heartbeat(current));
        assertEquals("TERMINAL_UNAVAILABLE", unavailable.code());
        assertEquals(Duration.ofSeconds(2), unavailable.retryAfter());
        assertEquals(1, unavailableDrains.get());

        TurnAttemptLeaseSupervisor retrySupervisor = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ZERO),
                executor(new AtomicInteger()));
        TurnAttemptHeartbeatOutcome.Retry retry = assertInstanceOf(
                TurnAttemptHeartbeatOutcome.Retry.class, retrySupervisor.heartbeat(current));
        assertEquals(Duration.ZERO, retry.retryAfter());
    }

    @Test
    void dueCheckUsesRenewWithinFromTheAcceptedAnchor() {
        FencedAttempt current = attempt();
        TurnAttemptLeaseSupervisor supervisor = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ZERO),
                executor(new AtomicInteger()));
        LeaseTimingAnchor anchor = new LeaseTimingAnchor(100L, current.lease());

        assertFalse(supervisor.isDue(anchor, 100L + current.lease().renewWithin().toNanos() - 1));
        assertTrue(supervisor.isDue(anchor, 100L + current.lease().renewWithin().toNanos()));
    }

    private static TurnV2TurnExecutor executor(AtomicInteger drains) {
        return new TurnV2TurnExecutor() {
            @Override
            public TurnAttemptCompletion execute(
                    TurnSubmission.ExecutionAccepted accepted,
                    UserTurnCommand command,
                    TurnEventSink events
            ) {
                return new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "TEST");
            }

            @Override
            public void disableWritesAndDrain(FencedAttempt attempt) {
                drains.incrementAndGet();
            }
        };
    }

    private static FencedAttempt attempt() {
        UserTurnCommand command = new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw", "runtime-1",
                TurnDeclarations.empty());
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", command.turnId()),
                AttemptLease.fromDatabaseClock(
                        "attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private static TurnStatusView status(FencedAttempt attempt, TurnStatus status) {
        return new TurnStatusView(
                attempt.key(), status, attempt.attemptId(), attempt.attemptEpoch(),
                status.isTerminal() ? status.name() : null, null,
                Instant.parse("2026-07-26T00:00:10Z"));
    }
}
