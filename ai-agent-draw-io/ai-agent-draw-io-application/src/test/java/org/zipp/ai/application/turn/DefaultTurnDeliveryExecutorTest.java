package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.execution.TurnAttemptCompletion;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnHandle;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DefaultTurnDeliveryExecutorTest {

    @Test
    void syncAndStreamAdaptersCanShareOneFacadeBoundary() {
        DiagramTurnFacade facade = (actor, command, events) -> new TurnSubmission.NotReady("PAUSED");
        TurnDeliveryExecutor executor = new DefaultTurnDeliveryExecutor(facade);
        UserTurnCommand command = new UserTurnCommand(
                "turn-1", "default", "diagram-1", "client-1", "draw", "runtime-1",
                TurnDeclarations.empty());

        TurnSubmission result = executor.execute(
                new AuthenticatedActor("owner-1", "cohort-1"), command, event -> { });

        assertSame(TurnSubmission.NotReady.class, result.getClass());
    }

    @Test
    void startsAnAcceptedAttemptWhenTheV2RunnerIsComposed() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger executions = new AtomicInteger();
            TurnV2TurnExecutor v2Executor = new TurnV2TurnExecutor() {
                @Override
                public TurnAttemptCompletion execute(
                        TurnSubmission.ExecutionAccepted accepted,
                        UserTurnCommand command,
                        TurnEventSink events
                ) {
                    executions.incrementAndGet();
                    return new TurnAttemptCompletion.PersistedTerminal(
                            new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", null, "{}"));
                }

                @Override
                public void disableWritesAndDrain(FencedAttempt attempt) {
                    // The handoff test does not exercise lease loss.
                }
            };
            TurnAttemptLeaseSupervisor heartbeat = new TurnAttemptLeaseSupervisor(
                    ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(30)),
                    v2Executor);
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    v2Executor, heartbeat, Runnable::run, scheduler);
            TurnSubmission.ExecutionAccepted accepted = accepted();
            DiagramTurnFacade facade = (actor, command, events) -> accepted;
            DefaultTurnDeliveryExecutor delivery = new DefaultTurnDeliveryExecutor(facade, runner);

            TurnDeliveryExecution execution = delivery.executeTracked(
                    new AuthenticatedActor("owner-1", "cohort-1"), command(), event -> { });
            TurnSubmission result = execution.submission();
            TurnHandle handle = execution.handle();

            assertSame(accepted, result);
            assertNotNull(handle);
            assertNotNull(handle.completion().toCompletableFuture().getNow(null));
            assertEquals(1, executions.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void resolvesTheAttemptRunnerWhenTheRequestExecutes() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger executions = new AtomicInteger();
            TurnV2TurnExecutor v2Executor = new TurnV2TurnExecutor() {
                @Override
                public TurnAttemptCompletion execute(
                        TurnSubmission.ExecutionAccepted accepted,
                        UserTurnCommand command,
                        TurnEventSink events
                ) {
                    executions.incrementAndGet();
                    return new TurnAttemptCompletion.PersistedTerminal(
                            new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", null, "{}"));
                }

                @Override
                public void disableWritesAndDrain(FencedAttempt attempt) {
                    // Lease loss is outside this lazy-composition regression test.
                }
            };
            TurnAttemptLeaseSupervisor heartbeat = new TurnAttemptLeaseSupervisor(
                    ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(30)),
                    v2Executor);
            AtomicReference<TurnAttemptExecutionRunner> runner = new AtomicReference<>();
            DiagramTurnFacade facade = (actor, command, events) -> accepted();
            DefaultTurnDeliveryExecutor delivery =
                    new DefaultTurnDeliveryExecutor(facade, runner::get);
            runner.set(new TurnAttemptExecutionRunner(
                    v2Executor, heartbeat, Runnable::run, scheduler));

            delivery.execute(
                    new AuthenticatedActor("owner-1", "cohort-1"), command(), event -> { });

            assertEquals(1, executions.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static TurnSubmission.ExecutionAccepted accepted() {
        FencedAttempt attempt = new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                1,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
        return new TurnSubmission.ExecutionAccepted(
                attempt.key(), attempt, new LeaseTimingAnchor(System.nanoTime(), attempt.lease()));
    }

    private static UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw", "runtime-1",
                TurnDeclarations.empty());
    }
}
