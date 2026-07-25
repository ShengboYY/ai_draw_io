package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnAttemptExecutionRunnerTest {

    @Test
    void completesTheAcceptedAttemptThroughTheSharedExecutor() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger delivered = new AtomicInteger();
            TurnV2TurnExecutor executor = executor((accepted, command, events) -> {
                events.publish(new TurnEvent("progress", "started", Instant.now()));
                return new TurnAttemptCompletion.PersistedTerminal(
                        new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", "payload", "{}"));
            }, ignored -> { });
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor, supervisor(executor, ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(
                            java.time.Duration.ofSeconds(30))), Runnable::run, scheduler);

            TurnHandle handle = runner.start(accepted(false), command(), event -> delivered.incrementAndGet());
            TurnAttemptCompletion.PersistedTerminal completion = assertInstanceOf(
                    TurnAttemptCompletion.PersistedTerminal.class,
                    handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));

            assertEquals(TurnStatus.COMPLETED, completion.outcome().status());
            assertEquals(1, delivered.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void detachingTheSubscriberDoesNotCancelExecution() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService execution = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch firstEvent = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger delivered = new AtomicInteger();
            TurnV2TurnExecutor executor = executor((accepted, command, events) -> {
                events.publish(new TurnEvent("progress", "before-detach", Instant.now()));
                firstEvent.countDown();
                release.await(1, TimeUnit.SECONDS);
                events.publish(new TurnEvent("progress", "after-detach", Instant.now()));
                return new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "TEST_COMPLETION");
            }, ignored -> { });
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor, supervisor(executor, ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(
                            java.time.Duration.ofSeconds(30))), execution, scheduler);

            TurnHandle handle = runner.start(accepted(false), command(), event -> delivered.incrementAndGet());
            assertTrue(firstEvent.await(1, TimeUnit.SECONDS));
            handle.detach();
            release.countDown();

            TurnAttemptCompletion.AttemptSelfAborted completion = assertInstanceOf(
                    TurnAttemptCompletion.AttemptSelfAborted.class,
                    handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals("TEST_COMPLETION", completion.code());
            assertEquals(1, delivered.get());
        } finally {
            execution.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void writerFailureDetachesDeliveryWithoutChangingTheExecutionCompletion() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger writes = new AtomicInteger();
            TurnV2TurnExecutor executor = executor((accepted, command, events) -> {
                events.publish(new TurnEvent("progress", "writer-fails", Instant.now()));
                return new TurnAttemptCompletion.PersistedTerminal(
                        new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", "payload", "{}"));
            }, ignored -> { });
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor, supervisor(executor, ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(
                            java.time.Duration.ofSeconds(30))), Runnable::run, scheduler);

            TurnHandle handle = runner.start(accepted(false), command(), event -> {
                writes.incrementAndGet();
                throw new IllegalStateException("disconnected writer");
            });

            TurnAttemptCompletion.PersistedTerminal completion = assertInstanceOf(
                    TurnAttemptCompletion.PersistedTerminal.class,
                    handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(TurnStatus.COMPLETED, completion.outcome().status());
            assertEquals(1, writes.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void heartbeatOwnershipLossCompletesTheHandleAndDrainsWrites() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService execution = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger drains = new AtomicInteger();
            TurnStatusRef status = new TurnStatusRef(attempt().key());
            TurnV2TurnExecutor executor = executor((accepted, command, events) -> {
                entered.countDown();
                release.await(1, TimeUnit.SECONDS);
                return new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "LATE_EXECUTOR_RESULT");
            }, ignored -> drains.incrementAndGet());
            TurnAttemptLeaseSupervisor supervisor = supervisor(
                    executor, ignored -> new TurnAttemptLeasePort.LeaseFenceLost(
                            new org.zipp.ai.application.turn.TurnStatusView(
                                    attempt().key(), TurnStatus.RUNNING, "attempt-1", 1,
                                    null, null, Instant.now())));
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor, supervisor, execution, scheduler);

            TurnHandle handle = runner.start(accepted(true), command(), ignored -> { });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            TurnAttemptCompletion.AttemptOwnershipLost completion = assertInstanceOf(
                    TurnAttemptCompletion.AttemptOwnershipLost.class,
                    handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));

            assertEquals(attempt().key(), completion.status().key());
            assertEquals(1, drains.get());
            release.countDown();
        } finally {
            execution.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void deadlineWinnerCompletesTheHandleWithThePersistedCancellation() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService execution = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger deadlineCalls = new AtomicInteger();
            TurnV2TurnExecutor executor = executor((accepted, command, events) -> {
                entered.countDown();
                release.await(1, TimeUnit.SECONDS);
                return new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "LATE_EXECUTOR_RESULT");
            }, ignored -> { });
            TurnAttemptDeadlineSupervisor deadlines = new TurnAttemptDeadlineSupervisor(
                    (ignoredAttempt, ignoredReason) -> {
                        deadlineCalls.incrementAndGet();
                        return new DeadlineCancelOutcome.Cancelled(
                                new PersistedTurnOutcome(TurnStatus.CANCELLED,
                                        "EXECUTION_DEADLINE", "deadline", null, "{}"));
                    }, new AttemptWriteGate());
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    executor, supervisor(executor, ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(
                            Duration.ofSeconds(30))), execution, scheduler);

            TurnHandle handle = runner.start(
                    accepted(false), command(), ignored -> { }, Duration.ZERO, deadlines);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            TurnAttemptCompletion.PersistedTerminal completion = assertInstanceOf(
                    TurnAttemptCompletion.PersistedTerminal.class,
                    handle.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));

            assertEquals(TurnStatus.CANCELLED, completion.outcome().status());
            assertEquals(1, deadlineCalls.get());
            release.countDown();
        } finally {
            execution.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    private static TurnAttemptLeaseSupervisor supervisor(
            TurnV2TurnExecutor executor,
            TurnAttemptLeasePort leases
    ) {
        return new TurnAttemptLeaseSupervisor(leases, executor);
    }

    private static TurnV2TurnExecutor executor(ExecutionBody body, DrainBody drain) {
        return new TurnV2TurnExecutor() {
            @Override
            public TurnAttemptCompletion execute(
                    TurnSubmission.ExecutionAccepted accepted,
                    UserTurnCommand command,
                    TurnEventSink events
            ) {
                try {
                    return body.execute(accepted, command, events);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new TurnAttemptCompletion.AttemptSelfAborted(
                            new TurnStatusRef(accepted.key()), "TEST_INTERRUPTED");
                }
            }

            @Override
            public void disableWritesAndDrain(FencedAttempt attempt) {
                drain.disable(attempt);
            }
        };
    }

    private static TurnSubmission.ExecutionAccepted accepted(boolean due) {
        FencedAttempt attempt = attempt();
        long started = System.nanoTime();
        if (due) {
            started -= attempt.lease().renewWithin().toNanos() + 1;
        }
        return new TurnSubmission.ExecutionAccepted(
                attempt.key(), attempt, new LeaseTimingAnchor(started, attempt.lease()));
    }

    private static UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw", "runtime-1",
                TurnDeclarations.empty());
    }

    private static FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                AttemptLease.fromDatabaseClock(
                        "attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    @FunctionalInterface
    private interface ExecutionBody {
        TurnAttemptCompletion execute(
                TurnSubmission.ExecutionAccepted accepted,
                UserTurnCommand command,
                TurnEventSink events
        ) throws InterruptedException;
    }

    @FunctionalInterface
    private interface DrainBody {
        void disable(FencedAttempt attempt);
    }
}
