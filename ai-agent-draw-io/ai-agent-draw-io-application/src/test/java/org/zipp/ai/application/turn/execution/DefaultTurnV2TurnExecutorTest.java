package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultTurnV2TurnExecutorTest {

    @Test
    void forwardsOnlyTheAcceptedAttemptToTheIsolatedCoordinator() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = new TurnSubmission.ExecutionAccepted(
                attempt.key(), attempt, new LeaseTimingAnchor(123L, attempt.lease()));
        TurnEventSink events = event -> { };
        TurnV2ExecutionOutcome expected = new TurnV2ExecutionOutcome.PreparationBlocked(
                new TurnV2PreHandlerOutcome.FenceLost(new TurnStatusRef(attempt.key())));

        TurnV2ExecutionOutcome actual = new DefaultTurnV2TurnExecutor(
                (receivedAttempt, receivedCommand, receivedEvents) -> {
                    assertSame(attempt, receivedAttempt);
                    assertSame(command, receivedCommand);
                    assertSame(events, receivedEvents);
                    return expected;
                }).execute(accepted, command, events);

        assertSame(expected, actual);
    }

    private static UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw a flow", "runtime-1",
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
}
