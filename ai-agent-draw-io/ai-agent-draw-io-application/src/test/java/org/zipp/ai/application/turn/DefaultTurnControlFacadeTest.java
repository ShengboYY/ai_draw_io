package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultTurnControlFacadeTest {

    @Test
    void rejectsCrossOwnerStatusBeforeCallingPort() {
        boolean[] called = {false};
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> {
                    called[0] = true;
                    return new TurnStatusQueryOutcome.Available(status(query.key()));
                },
                (actor, command) -> new CancelTurnOutcome.Rejected("TEST_ONLY"),
                attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1)),
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY"),
                key -> new TurnAttemptTakeoverPort.Rejected("TEST_ONLY"),
                new OpenAdmissionBarrier());

        assertThrows(IllegalStateException.class,
                () -> facade.status(
                        new AuthenticatedActor("owner-a", "cohort-a"),
                        new TurnStatusQuery(new TurnKey("owner-b", "conversation-1", "turn-1"))));
        assertEquals(false, called[0]);
    }

    @Test
    void rejectsCrossOwnerCancellationBeforeCallingPort() {
        boolean[] called = {false};
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> new TurnStatusQueryOutcome.Available(status(query.key())),
                (actor, command) -> {
                    called[0] = true;
                    return new CancelTurnOutcome.Cancelled(status(command.key()));
                },
                attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1)),
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY"),
                key -> new TurnAttemptTakeoverPort.Rejected("TEST_ONLY"),
                new OpenAdmissionBarrier());

        CancelTurnOutcome.Rejected rejected = assertInstanceOf(
                CancelTurnOutcome.Rejected.class,
                facade.cancel(
                        new AuthenticatedActor("owner-a", "cohort-a"),
                        new CancelTurnCommand(
                                new TurnKey("owner-b", "conversation-1", "turn-1"), "user-requested")));

        assertEquals("OWNER_MISMATCH", rejected.code());
        assertEquals(false, called[0]);
    }

    @Test
    void rejectsTakeoverForAnotherAuthenticatedOwnerBeforeCallingPort() {
        boolean[] called = {false};
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> new TurnStatusQueryOutcome.Available(status(query.key())),
                (actor, command) -> new CancelTurnOutcome.Rejected("TEST_ONLY"),
                attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1)),
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY"),
                key -> {
                    called[0] = true;
                    return new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
                },
                new OpenAdmissionBarrier());

        TurnAttemptTakeoverPort.TakeoverOutcome outcome = facade.takeover(
                new AuthenticatedActor("owner-a", "cohort-a"),
                new TurnKey("owner-b", "conversation-1", "turn-1"));

        TurnAttemptTakeoverPort.Rejected rejected = assertInstanceOf(
                TurnAttemptTakeoverPort.Rejected.class, outcome);
        assertEquals("OWNER_MISMATCH", rejected.code());
        assertEquals(false, called[0]);
    }

    @Test
    void rejectsTakeoverWhileAdmissionIsClosedBeforeCallingPort() {
        boolean[] called = {false};
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> new TurnStatusQueryOutcome.Available(status(query.key())),
                (actor, command) -> new CancelTurnOutcome.Rejected("TEST_ONLY"),
                attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1)),
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY"),
                key -> {
                    called[0] = true;
                    return new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
                },
                new ClosedAdmissionBarrier());

        TurnAttemptTakeoverPort.Rejected rejected = assertInstanceOf(
                TurnAttemptTakeoverPort.Rejected.class,
                facade.takeover(
                        new AuthenticatedActor("owner-a", "cohort-a"),
                        new TurnKey("owner-a", "conversation-1", "turn-1")));

        assertEquals("TURN_INSTANCE_NOT_READY", rejected.code());
        assertEquals(false, called[0]);
    }

    @Test
    void delegatesStatusAndFencedControlsThroughOneFacade() {
        TurnKey key = new TurnKey("owner-a", "conversation-1", "turn-1");
        TurnStatusView expectedStatus = status(key);
        TurnAttemptLeasePort.HeartbeatOutcome expectedHeartbeat =
                new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(2));
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> new TurnStatusQueryOutcome.Available(expectedStatus),
                (actor, command) -> new CancelTurnOutcome.Rejected("CANCELLED"),
                attempt -> expectedHeartbeat,
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("DEADLINE"),
                ignored -> new TurnAttemptTakeoverPort.Rejected("TAKEOVER"),
                new OpenAdmissionBarrier());
        FencedAttempt attempt = new FencedAttempt(
                key,
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                0,
                "input",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));

        assertEquals(new TurnStatusQueryOutcome.Available(expectedStatus), facade.status(
                new AuthenticatedActor("owner-a", "cohort-a"), new TurnStatusQuery(key)));
        assertEquals(expectedHeartbeat, facade.heartbeat(attempt));
        assertInstanceOf(DeadlineCancelOutcome.TransientFailure.class,
                facade.cancelAtDeadline(attempt, AttemptDeadlineReason.EXECUTION_DEADLINE));
    }

    private static TurnStatusView status(TurnKey key) {
        return new TurnStatusView(key, TurnStatus.RUNNING, "attempt-1", 1,
                null, null, Instant.parse("2026-07-26T00:00:00Z"));
    }

    private static final class OpenAdmissionBarrier implements AdmissionBarrier {
        @Override
        public void pauseAndDrain() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class ClosedAdmissionBarrier implements AdmissionBarrier {
        @Override
        public void pauseAndDrain() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isOpen() {
            return false;
        }
    }
}
