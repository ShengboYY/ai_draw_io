package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultTurnControlFacadeTest {

    @Test
    void rejectsTakeoverForAnotherAuthenticatedOwnerBeforeCallingPort() {
        boolean[] called = {false};
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> status(query.key()),
                (actor, command) -> new CancelTurnOutcome.Rejected("TEST_ONLY"),
                attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1)),
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY"),
                key -> {
                    called[0] = true;
                    return new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
                });

        TurnAttemptTakeoverPort.TakeoverOutcome outcome = facade.takeover(
                new AuthenticatedActor("owner-a", "cohort-a"),
                new TurnKey("owner-b", "conversation-1", "turn-1"));

        TurnAttemptTakeoverPort.Rejected rejected = assertInstanceOf(
                TurnAttemptTakeoverPort.Rejected.class, outcome);
        assertEquals("OWNER_MISMATCH", rejected.code());
        assertEquals(false, called[0]);
    }

    @Test
    void delegatesStatusAndFencedControlsThroughOneFacade() {
        TurnKey key = new TurnKey("owner-a", "conversation-1", "turn-1");
        TurnStatusView expectedStatus = status(key);
        TurnAttemptLeasePort.HeartbeatOutcome expectedHeartbeat =
                new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(2));
        TurnControlFacade facade = new DefaultTurnControlFacade(
                (actor, query) -> expectedStatus,
                (actor, command) -> new CancelTurnOutcome.Rejected("CANCELLED"),
                attempt -> expectedHeartbeat,
                (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("DEADLINE"),
                ignored -> new TurnAttemptTakeoverPort.Rejected("TAKEOVER"));
        FencedAttempt attempt = new FencedAttempt(
                key,
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                0,
                "input",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));

        assertEquals(expectedStatus, facade.status(
                new AuthenticatedActor("owner-a", "cohort-a"), new TurnStatusQuery(key)));
        assertEquals(expectedHeartbeat, facade.heartbeat(attempt));
        assertInstanceOf(DeadlineCancelOutcome.TransientFailure.class,
                facade.cancelAtDeadline(attempt, AttemptDeadlineReason.EXECUTION_DEADLINE));
    }

    private static TurnStatusView status(TurnKey key) {
        return new TurnStatusView(key, TurnStatus.RUNNING, "attempt-1", 1,
                null, null, Instant.parse("2026-07-26T00:00:00Z"));
    }
}
