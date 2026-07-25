package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQuery;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.CancelTurnCommand;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.TurnStatusView;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TurnAttemptRecoveryCoordinatorTest {

    @Test
    void takeoverRebuildsPinnedInputBeforeStartingTheRunner() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
            FencedAttempt attempt = attempt(key);
            UserTurnCommand expected = new UserTurnCommand(
                    "turn-1", "conversation-1", "diagram-1", "client-1", "draw", null,
                    TurnDeclarations.empty());
            AtomicReference<UserTurnCommand> executed = new AtomicReference<>();
            TurnV2TurnExecutor executor = new TurnV2TurnExecutor() {
                @Override
                public TurnAttemptCompletion execute(
                        TurnSubmission.ExecutionAccepted accepted,
                        UserTurnCommand command,
                        TurnEventSink events
                ) {
                    executed.set(command);
                    return new TurnAttemptCompletion.PersistedTerminal(
                            new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", null, "{}"));
                }

                @Override
                public void disableWritesAndDrain(FencedAttempt ignored) {
                }
            };
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor,
                    new TurnAttemptLeaseSupervisor(
                            ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(
                                    java.time.Duration.ofSeconds(30)), executor),
                    Runnable::run,
                    scheduler);
            TurnAttemptRecoveryCoordinator coordinator = new TurnAttemptRecoveryCoordinator(
                    control(new TurnAttemptTakeoverPort.Claimed(attempt)),
                    ignored -> new TurnAttemptInputRecoveryPort.Recovered(expected),
                    runner);

            TurnAttemptRecoveryOutcome.Started started = assertInstanceOf(
                    TurnAttemptRecoveryOutcome.Started.class,
                    coordinator.start(
                            new AuthenticatedActor("owner-1", "cohort-1"), key, ignored -> { }));

            assertEquals(attempt, started.attempt());
            assertEquals(expected, executed.get());
            assertInstanceOf(TurnAttemptCompletion.PersistedTerminal.class,
                    started.handle().completion().toCompletableFuture().join());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static TurnControlFacade control(TurnAttemptTakeoverPort.TakeoverOutcome takeover) {
        return new TurnControlFacade() {
            @Override
            public TurnStatusQueryOutcome status(AuthenticatedActor actor, TurnStatusQuery query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TurnAttemptLeasePort.HeartbeatOutcome heartbeat(FencedAttempt attempt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DeadlineCancelOutcome cancelAtDeadline(
                    FencedAttempt attempt, AttemptDeadlineReason reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TurnAttemptTakeoverPort.TakeoverOutcome takeover(
                    AuthenticatedActor actor, TurnKey key) {
                return takeover;
            }
        };
    }

    private static FencedAttempt attempt(TurnKey key) {
        return new FencedAttempt(
                key,
                new AttemptLease("attempt-2", 2,
                        Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                1,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "policy-hash"));
    }
}
