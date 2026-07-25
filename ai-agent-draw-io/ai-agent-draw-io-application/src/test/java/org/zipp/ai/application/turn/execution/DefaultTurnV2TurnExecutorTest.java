package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
                }, ignored -> new FencedCommitOutcome.Rejected("unexpected"))
                .execute(accepted, command, events);

        assertSame(expected, actual);
    }

    @Test
    void commitsPreHandlerTerminalWithTheAcceptedAttempt() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);

        TurnV2ExecutionOutcome.Committed outcome = assertInstanceOf(
                TurnV2ExecutionOutcome.Committed.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) ->
                                new TurnV2ExecutionOutcome.PreparationBlocked(
                                        new TurnV2PreHandlerOutcome.Terminal(
                                                "CONTEXT_READ_SET_REVOKED", "revoked")),
                        commandToCommit -> {
                            assertSame(attempt, commandToCommit.attempt());
                            assertEquals(TurnStatus.FAILED, commandToCommit.terminalStatus());
                            assertEquals("CONTEXT_READ_SET_REVOKED", commandToCommit.terminalCode());
                            return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                                    TurnStatus.FAILED, commandToCommit.terminalCode(),
                                    commandToCommit.terminalPayloadType(), null, "{}"));
                        })
                        .execute(accepted, command, event -> { }));

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome.outcome());
    }

    @Test
    void commitsUnsupportedRouteAsRejectedWithoutInvokingPlain() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());

        TurnV2ExecutionOutcome.Committed outcome = assertInstanceOf(
                TurnV2ExecutionOutcome.Committed.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) ->
                                new TurnV2ExecutionOutcome.NotDispatched(
                                        new TurnRouteDecision.Unsupported(new PrePlanOutcome.Unsupported(
                                                "UNSUPPORTED_ACTION",
                                                new PlanningLineageFingerprint("b".repeat(64)),
                                                readSet.digest(), attempt.inputBindingDigest())),
                                        "UNSUPPORTED_ROUTE_NOT_EXECUTABLE"),
                        commandToCommit -> {
                            assertEquals(TurnStatus.REJECTED, commandToCommit.terminalStatus());
                            assertEquals("UNSUPPORTED_ACTION", commandToCommit.terminalCode());
                            return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                                    TurnStatus.REJECTED, commandToCommit.terminalCode(),
                                    commandToCommit.terminalPayloadType(), null, "{}"));
                        })
                        .execute(accepted, command, event -> { }));

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome.outcome());
    }

    private static UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw a flow", "runtime-1",
                TurnDeclarations.empty());
    }

    private static TurnSubmission.ExecutionAccepted accepted(FencedAttempt attempt) {
        return new TurnSubmission.ExecutionAccepted(
                attempt.key(), attempt, new LeaseTimingAnchor(123L, attempt.lease()));
    }

    private static FencedAttempt attempt(UserTurnCommand command) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", command.turnId()),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                17,
                TurnInputBindingDigestCalculator.current(command),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
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
