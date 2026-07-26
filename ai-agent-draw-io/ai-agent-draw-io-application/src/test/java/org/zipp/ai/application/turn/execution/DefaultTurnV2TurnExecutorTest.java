package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AttemptWriteGate;
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
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.demand.NeedsSourceClarification;
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
        TurnAttemptCompletion actual = new DefaultTurnV2TurnExecutor(
                (receivedAttempt, receivedCommand, receivedEvents) -> {
                    assertSame(attempt, receivedAttempt);
                    assertSame(command, receivedCommand);
                    assertSame(events, receivedEvents);
                    return new TurnV2ExecutionOutcome.PreparationBlocked(
                            new TurnV2PreHandlerOutcome.FenceLost(new TurnStatusRef(attempt.key())));
                }, ignored -> new FencedCommitOutcome.Rejected("unexpected"))
                .execute(accepted, command, events);

        TurnAttemptCompletion.AttemptOwnershipLost lost = assertInstanceOf(
                TurnAttemptCompletion.AttemptOwnershipLost.class, actual);
        assertEquals(attempt.key(), lost.status().key());
    }

    @Test
    void commitsPreHandlerTerminalWithTheAcceptedAttempt() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);

        TurnAttemptCompletion.PersistedTerminal outcome = assertInstanceOf(
                TurnAttemptCompletion.PersistedTerminal.class,
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

        assertEquals(TurnStatus.FAILED, outcome.outcome().status());
    }

    @Test
    void durableAlreadyTerminalPreparationSkipsTheTerminalCommitPort() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        int[] commits = {0};

        TurnAttemptCompletion.PersistedTerminal outcome = assertInstanceOf(
                TurnAttemptCompletion.PersistedTerminal.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) ->
                                new TurnV2ExecutionOutcome.PreparationBlocked(
                                        new TurnV2PreHandlerOutcome.AlreadyTerminal(
                                                new PersistedTurnOutcome(
                                                        TurnStatus.CANCELLED, "CANCELLED_BY_USER",
                                                        "cancel", null, "{}"))),
                        ignored -> {
                            commits[0]++;
                            return new FencedCommitOutcome.Rejected("unexpected");
                        })
                        .execute(accepted, command, event -> { }));

        assertEquals(TurnStatus.CANCELLED, outcome.outcome().status());
        assertEquals(0, commits[0]);
    }

    @Test
    void commitsUnsupportedRouteAsRejectedWithoutInvokingPlain() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());

        TurnAttemptCompletion.PersistedTerminal outcome = assertInstanceOf(
                TurnAttemptCompletion.PersistedTerminal.class,
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

        assertEquals(TurnStatus.REJECTED, outcome.outcome().status());
    }

    @Test
    void commitsClarificationAsDeferredUntilDurableAuthorityExists() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());

        TurnAttemptCompletion.PersistedTerminal outcome = assertInstanceOf(
                TurnAttemptCompletion.PersistedTerminal.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) ->
                                new TurnV2ExecutionOutcome.NotDispatched(
                                        new TurnRouteDecision.Clarification(
                                                new PrePlanOutcome.NeedsClarification(
                                                        new NeedsSourceClarification(
                                                                "AMBIGUOUS_SOURCE_DEMAND", java.util.List.of()),
                                                        new PlanningLineageFingerprint("b".repeat(64)),
                                                        readSet.digest(), attempt.inputBindingDigest())),
                                        "CLARIFICATION_DEFERRED"),
                        commandToCommit -> {
                            assertEquals(TurnStatus.REJECTED, commandToCommit.terminalStatus());
                            assertEquals("CLARIFICATION_DEFERRED", commandToCommit.terminalCode());
                            return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                                    TurnStatus.REJECTED, commandToCommit.terminalCode(),
                                    commandToCommit.terminalPayloadType(), null, "{}"));
                        })
                        .execute(accepted, command, event -> { }));

        assertEquals(TurnStatus.REJECTED, outcome.outcome().status());
    }

    @Test
    void disabledAttemptCannotCommitPreHandlerTerminal() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        AttemptWriteGate gate = new AttemptWriteGate();
        gate.disableAndDrain(attempt);
        int[] commits = {0};

        TurnAttemptCompletion.AttemptSelfAborted outcome = assertInstanceOf(
                TurnAttemptCompletion.AttemptSelfAborted.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) ->
                                new TurnV2ExecutionOutcome.PreparationBlocked(
                                        new TurnV2PreHandlerOutcome.Terminal(
                                                "CONTEXT_READ_SET_REVOKED", "revoked")),
                        commandToCommit -> {
                            commits[0]++;
                            return new FencedCommitOutcome.Rejected("unexpected");
                        },
                        gate)
                        .execute(accepted, command, event -> { }));

        assertEquals("TURN_WRITE_GATE_DISABLED", outcome.code());
        assertEquals(0, commits[0]);
    }

    @Test
    void coordinatorFailureSelfAbortsWithoutSubmittingAProductTerminal() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        int[] commits = {0};

        TurnAttemptCompletion.AttemptSelfAborted outcome = assertInstanceOf(
                TurnAttemptCompletion.AttemptSelfAborted.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) -> {
                            throw new IllegalStateException("model unavailable");
                        },
                        commandToCommit -> {
                            commits[0]++;
                            return new FencedCommitOutcome.Rejected("unexpected");
                        })
                        .execute(accepted, command, event -> { }));

        assertEquals("TURN_EXECUTION_FAILED", outcome.code());
        assertEquals(0, commits[0]);
    }

    @Test
    void terminalDecoderUnavailableBecomesStatusOnlyCompletion() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        TurnSubmission.ExecutionAccepted accepted = accepted(attempt);
        TurnStatusView status = new TurnStatusView(
                attempt.key(), TurnStatus.RUNNING, attempt.attemptId(), attempt.attemptEpoch(),
                null, null, Instant.parse("2026-07-26T00:01:00Z"));

        TurnAttemptCompletion.StatusOnly outcome = assertInstanceOf(
                TurnAttemptCompletion.StatusOnly.class,
                new DefaultTurnV2TurnExecutor(
                        (ignoredAttempt, ignoredCommand, ignoredEvents) ->
                                new TurnV2ExecutionOutcome.Committed(
                                        new FencedCommitOutcome.TerminalUnavailable(
                                                status, "TERMINAL_PAYLOAD_SCHEMA_UNSUPPORTED")),
                        ignored -> new FencedCommitOutcome.Rejected("unexpected"))
                        .execute(accepted, command, event -> { }));

        assertEquals(attempt.key(), outcome.status().key());
        assertEquals("TERMINAL_PAYLOAD_SCHEMA_UNSUPPORTED", outcome.code());
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
