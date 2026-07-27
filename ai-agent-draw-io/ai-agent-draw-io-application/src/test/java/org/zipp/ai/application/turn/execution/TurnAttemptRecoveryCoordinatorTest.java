package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AdmissionBarrier;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.DefaultTurnControlFacade;
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
import org.zipp.ai.application.turn.TurnLifecycleTraceEvent;
import org.zipp.ai.application.turn.TurnLifecycleTraceType;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recoveredAttemptRunsUnderItsOwnTelemetryRun() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
            FencedAttempt attempt = attempt(key);
            UserTurnCommand expected = new UserTurnCommand(
                    "turn-1", "conversation-1", "diagram-1", "client-1", "draw", null,
                    TurnDeclarations.empty());
            AtomicBoolean boundDuringDispatch = new AtomicBoolean();
            AtomicReference<Throwable> completedWith = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();
            RecordingRecoveryTelemetry telemetry =
                    new RecordingRecoveryTelemetry(completed, completedWith);
            TurnV2TurnExecutor executor = new TurnV2TurnExecutor() {
                @Override
                public TurnAttemptCompletion execute(
                        TurnSubmission.ExecutionAccepted accepted,
                        UserTurnCommand command,
                        TurnEventSink events
                ) {
                    boundDuringDispatch.set(telemetry.bound.get());
                    return new TurnAttemptCompletion.AttemptSelfAborted(
                            new org.zipp.ai.application.turn.TurnStatusRef(accepted.key()),
                            "TURN_EXECUTION_FAILED");
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
                    runner,
                    event -> { },
                    telemetry);

            TurnAttemptRecoveryOutcome.Started started = assertInstanceOf(
                    TurnAttemptRecoveryOutcome.Started.class,
                    coordinator.start(
                            new AuthenticatedActor("owner-1", "cohort-1"), key, ignored -> { }));
            started.handle().completion().toCompletableFuture().join();

            // The pool captures the dispatching thread's context, so the run has to be bound before
            // the attempt starts or the model calls it makes are unattributable.
            assertTrue(boundDuringDispatch.get());
            assertFalse(telemetry.bound.get());
            assertTrue(completed.get());
            assertEquals("TURN_EXECUTION_FAILED", completedWith.get().getMessage());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static final class RecordingRecoveryTelemetry implements TurnRecoveryTelemetryPort {
        private final AtomicBoolean bound = new AtomicBoolean();
        private final AtomicBoolean completed;
        private final AtomicReference<Throwable> completedWith;

        private RecordingRecoveryTelemetry(AtomicBoolean completed,
                                           AtomicReference<Throwable> completedWith) {
            this.completed = completed;
            this.completedWith = completedWith;
        }

        @Override
        public TurnRecoveryRun open(FencedAttempt attempt) {
            bound.set(true);
            return new TurnRecoveryRun() {
                @Override
                public void close() {
                    bound.set(false);
                }

                @Override
                public void complete(Throwable failure) {
                    completed.set(true);
                    completedWith.set(failure);
                }
            };
        }
    }

    @Test
    void realControlFacadeTracesTakeoverThroughRunnerCompletion() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
            FencedAttempt attempt = attempt(key);
            UserTurnCommand expected = new UserTurnCommand(
                    "turn-1", "conversation-1", "diagram-1", "client-1", "draw", null,
                    TurnDeclarations.empty());
            AtomicReference<UserTurnCommand> executed = new AtomicReference<>();
            CopyOnWriteArrayList<TurnLifecycleTraceEvent> traces = new CopyOnWriteArrayList<>();
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
            TurnControlFacade facade = new DefaultTurnControlFacade(
                    (actor, query) -> new TurnStatusQueryOutcome.Available(status(query.key())),
                    (actor, command) -> new CancelTurnOutcome.Rejected("UNUSED"),
                    ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(
                            java.time.Duration.ofSeconds(30)),
                    (ignored, reason) -> new DeadlineCancelOutcome.TransientFailure("UNUSED"),
                    ignored -> new TurnAttemptTakeoverPort.Claimed(attempt),
                    new OpenAdmissionBarrier(),
                    (ignoredKey, ignoredOutcome) -> { },
                    traces::add);
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor,
                    new TurnAttemptLeaseSupervisor(facade::heartbeat, executor),
                    Runnable::run,
                    scheduler,
                    traces::add);
            TurnAttemptRecoveryCoordinator coordinator = new TurnAttemptRecoveryCoordinator(
                    facade,
                    ignored -> new TurnAttemptInputRecoveryPort.Recovered(expected),
                    runner,
                    traces::add);

            TurnAttemptRecoveryOutcome.Started started = assertInstanceOf(
                    TurnAttemptRecoveryOutcome.Started.class,
                    coordinator.start(
                            new AuthenticatedActor("owner-1", "cohort-1"), key, ignored -> { }));

            TurnAttemptCompletion.PersistedTerminal completion = assertInstanceOf(
                    TurnAttemptCompletion.PersistedTerminal.class,
                    started.handle().completion().toCompletableFuture().join());
            assertEquals(expected, executed.get());
            assertEquals(TurnStatus.COMPLETED, completion.outcome().status());
            assertEquals(List.of(
                            TurnLifecycleTraceType.TAKEOVER,
                            TurnLifecycleTraceType.ATTEMPT_STARTED,
                            TurnLifecycleTraceType.ATTEMPT_COMPLETED),
                    traces.stream().map(TurnLifecycleTraceEvent::type).toList());

            // Every lifecycle event must remain tied to the recovered fenced attempt.
            TurnLifecycleTraceEvent takeover = traces.get(0);
            TurnLifecycleTraceEvent completed = traces.get(2);
            assertEquals(attempt.attemptId(), takeover.attemptId());
            assertEquals(attempt.attemptEpoch(), takeover.attemptEpoch());
            assertEquals(attempt.policy().policyHash(), takeover.policyHash());
            assertEquals(attempt.inputBindingDigest(), takeover.inputBindingDigest());
            assertEquals("CLAIMED", takeover.outcomeCode());
            assertEquals(TurnStatus.COMPLETED, completed.outcomeStatus());
            assertEquals("DONE", completed.outcomeCode());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void inputRecoveryFailureIsTracedAndNeverStartsTheRunner() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
            FencedAttempt attempt = attempt(key);
            AtomicBoolean executed = new AtomicBoolean();
            TurnV2TurnExecutor executor = new TurnV2TurnExecutor() {
                @Override
                public TurnAttemptCompletion execute(
                        TurnSubmission.ExecutionAccepted accepted,
                        UserTurnCommand command,
                        TurnEventSink events
                ) {
                    executed.set(true);
                    return new TurnAttemptCompletion.AttemptSelfAborted(
                            new org.zipp.ai.application.turn.TurnStatusRef(accepted.key()), "UNEXPECTED");
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
            CopyOnWriteArrayList<TurnLifecycleTraceEvent> traces = new CopyOnWriteArrayList<>();
            TurnAttemptRecoveryCoordinator coordinator = new TurnAttemptRecoveryCoordinator(
                    control(new TurnAttemptTakeoverPort.Claimed(attempt)),
                    ignored -> new TurnAttemptInputRecoveryPort.Unavailable("INPUT_BINDING_UNAVAILABLE"),
                    runner,
                    traces::add);

            TurnAttemptRecoveryOutcome.Unavailable outcome = assertInstanceOf(
                    TurnAttemptRecoveryOutcome.Unavailable.class,
                    coordinator.start(
                            new AuthenticatedActor("owner-1", "cohort-1"), key, ignored -> { }));

            assertEquals("INPUT_BINDING_UNAVAILABLE", outcome.code());
            assertFalse(executed.get());
            assertTrue(traces.stream().anyMatch(event ->
                    event.type() == TurnLifecycleTraceType.TAKEOVER
                            && "INPUT_BINDING_UNAVAILABLE".equals(event.outcomeCode())));
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

    private static TurnStatusView status(TurnKey key) {
        return new TurnStatusView(key, TurnStatus.RUNNING, "attempt-2", 2,
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
}
