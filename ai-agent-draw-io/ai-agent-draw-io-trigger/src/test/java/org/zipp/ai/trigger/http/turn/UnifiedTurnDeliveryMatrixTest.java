package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.DiagramTurnFacade;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnDeliveryExecutor;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.execution.TurnAttemptCompletion;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 gate: all transport variants must observe the same attempt result. The
 * stream writer is deliberately made to fail so delivery cannot affect the
 * application completion.
 */
class UnifiedTurnDeliveryMatrixTest {

    private enum PlanKind {
        PLAIN,
        DIRECT,
        GROUNDED,
        EVIDENCE_ANSWER
    }

    private record CompletionCase(
            String name,
            Class<? extends TurnAttemptCompletion> type,
            CompletionFactory factory
    ) {
    }

    @FunctionalInterface
    private interface CompletionFactory {
        TurnAttemptCompletion create(TurnKey key);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("transportMatrix")
    void syncAndNdjsonUseOneExecutorAndKeepTheSameAttemptCompletion(
            PlanKind plan,
            CompletionCase completionCase
    ) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-" + plan.name());
            List<UserTurnCommand> commands = new ArrayList<>();
            List<TurnAttemptCompletion> completions = new ArrayList<>();
            AtomicInteger writerCalls = new AtomicInteger();
            TurnSubmission.ExecutionAccepted accepted = accepted(key);

            TurnV2TurnExecutor attemptExecutor = new TurnV2TurnExecutor() {
                @Override
                public TurnAttemptCompletion execute(
                        TurnSubmission.ExecutionAccepted ignored,
                        UserTurnCommand command,
                        TurnEventSink events
                ) {
                    commands.add(command);
                    events.publish(new TurnEvent(
                            "progress", plan.name(), Instant.parse("2026-07-26T00:00:00Z")));
                    TurnAttemptCompletion completion = completionCase.factory().create(key);
                    completions.add(completion);
                    return completion;
                }

                @Override
                public void disableWritesAndDrain(FencedAttempt ignored) {
                    // This matrix exercises delivery, not lease-loss write draining.
                }
            };
            TurnAttemptLeaseSupervisor heartbeat = new TurnAttemptLeaseSupervisor(
                    ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(30)),
                    attemptExecutor);
            TurnAttemptExecutionRunner runner = new TurnAttemptExecutionRunner(
                    attemptExecutor, heartbeat, Runnable::run, scheduler);
            DiagramTurnFacade facade = (actor, command, events) -> accepted;
            TurnDeliveryExecutor delivery = new org.zipp.ai.application.turn.DefaultTurnDeliveryExecutor(
                    facade, runner);
            TurnHttpDeliveryAdapter adapter = new TurnHttpDeliveryAdapter(
                    new TurnHttpRequestTranslator(), delivery);

            TurnHttpDeliveryResult sync = adapter.executeSync(actor(), request(key, plan));
            TurnSubmission stream = adapter.executeNdjson(
                    actor(), request(key, plan), line -> {
                        writerCalls.incrementAndGet();
                        throw new IllegalStateException("client disconnected");
                    });

            assertSame(accepted, sync.submission());
            assertSame(accepted, stream);
            assertEquals(commands.get(0), commands.get(1));
            assertEquals(2, completions.size());
            assertEquals(completionCase.type(), completions.get(0).getClass());
            assertEquals(completions.get(0), completions.get(1));
            assertEquals(1, sync.events().size());
            assertEquals(1, writerCalls.get());
            assertTrue(sync.events().get(0).payload().equals(plan.name()));
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static Stream<Arguments> transportMatrix() {
        List<CompletionCase> completions = List.of(
                new CompletionCase(
                        "persisted-terminal",
                        TurnAttemptCompletion.PersistedTerminal.class,
                        key -> new TurnAttemptCompletion.PersistedTerminal(
                                new PersistedTurnOutcome(
                                        TurnStatus.COMPLETED, "DONE", "plain", null, "{}"))),
                new CompletionCase(
                        "ownership-lost",
                        TurnAttemptCompletion.AttemptOwnershipLost.class,
                        key -> new TurnAttemptCompletion.AttemptOwnershipLost(
                                new TurnStatusRef(key))),
                new CompletionCase(
                        "self-aborted",
                        TurnAttemptCompletion.AttemptSelfAborted.class,
                        key -> new TurnAttemptCompletion.AttemptSelfAborted(
                                new TurnStatusRef(key), "EXECUTION_FAILED")),
                new CompletionCase(
                        "status-only-unavailable",
                        TurnAttemptCompletion.StatusOnly.class,
                        key -> new TurnAttemptCompletion.StatusOnly(
                                new TurnStatusRef(key), "TERMINAL_SCHEMA_UNAVAILABLE")));
        return Stream.of(PlanKind.values())
                .flatMap(plan -> completions.stream()
                        .map(completion -> Arguments.of(plan, completion)));
    }

    private static AuthenticatedActor actor() {
        return new AuthenticatedActor("owner-1", "cohort-1");
    }

    private static TurnHttpRequest request(TurnKey key, PlanKind plan) {
        return new TurnHttpRequest(
                key.turnId(), key.canonicalConversationId(), "diagram-1", "client-" + plan.name(),
                plan.name(), null, List.of(), null, List.of());
    }

    private static TurnSubmission.ExecutionAccepted accepted(TurnKey key) {
        FencedAttempt attempt = new FencedAttempt(
                key,
                new AttemptLease("attempt-1", 1,
                        Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                1,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
        return new TurnSubmission.ExecutionAccepted(
                key, attempt, new LeaseTimingAnchor(System.nanoTime(), attempt.lease()));
    }
}
