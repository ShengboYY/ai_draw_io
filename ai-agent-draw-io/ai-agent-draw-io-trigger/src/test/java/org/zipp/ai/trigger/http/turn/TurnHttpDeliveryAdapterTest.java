package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnDeliveryExecutor;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnHttpDeliveryAdapterTest {

    @Test
    void submissionOnlyDeliveryDoesNotSubscribeTheHttpResponseToProgress() {
        AtomicInteger executionCalls = new AtomicInteger();
        TurnSubmission expected = new TurnSubmission.NotReady("TURN_INSTANCE_NOT_READY");
        TurnHttpDeliveryAdapter adapter = new TurnHttpDeliveryAdapter(
                new TurnHttpRequestTranslator(),
                (actor, command, events) -> {
                    executionCalls.incrementAndGet();
                    events.publish(new TurnEvent(
                            "progress", "must-not-be-written", Instant.parse("2026-07-26T00:00:00Z")));
                    return expected;
                });

        TurnSubmission actual = adapter.executeSubmission(
                new AuthenticatedActor("owner-1", "cohort-1"), request());

        assertSame(expected, actual);
        assertEquals(1, executionCalls.get());
    }

    @Test
    void concurrentSyncAndSubmissionShareTheSameTurnClaimBoundary() throws Exception {
        CyclicBarrier bothRequestsTranslated = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        List<UserTurnCommand> commands = Collections.synchronizedList(new ArrayList<>());
        TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
        TurnSubmission.ExecutionAccepted accepted = accepted(key);
        TurnStatusView running = new TurnStatusView(
                key, TurnStatus.RUNNING, "attempt-1", 1, null, null,
                Instant.parse("2026-07-26T00:00:00Z"));
        TurnDeliveryExecutor executor = (actor, command, events) -> {
            commands.add(command);
            try {
                bothRequestsTranslated.await(2, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            return calls.getAndIncrement() == 0
                    ? accepted
                    : new TurnSubmission.AlreadyRunning(key, running);
        };
        TurnHttpDeliveryAdapter adapter = new TurnHttpDeliveryAdapter(
                new TurnHttpRequestTranslator(), executor);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TurnHttpDeliveryResult> sync = pool.submit(
                    () -> adapter.executeSync(new AuthenticatedActor("owner-1", "cohort-1"), request()));
            Future<TurnSubmission> submission = pool.submit(
                    () -> adapter.executeSubmission(new AuthenticatedActor("owner-1", "cohort-1"), request()));

            TurnHttpDeliveryResult syncResult = sync.get(2, TimeUnit.SECONDS);
            TurnSubmission submissionResult = submission.get(2, TimeUnit.SECONDS);

            assertEquals(2, commands.size());
            assertEquals(commands.get(0), commands.get(1));
            List<TurnSubmission> outcomes = List.of(syncResult.submission(), submissionResult);
            assertTrue(outcomes.stream().anyMatch(TurnSubmission.ExecutionAccepted.class::isInstance));
            assertTrue(outcomes.stream().anyMatch(TurnSubmission.AlreadyRunning.class::isInstance));
            assertEquals(org.springframework.http.HttpStatus.ACCEPTED, syncResult.responseStatus());
        } finally {
            pool.shutdownNow();
        }
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

    private static TurnHttpRequest request() {
        return new TurnHttpRequest(
                "turn-1", "conversation:conversation-1", "diagram-1", "client-1", "draw it",
                null, List.of(), null, List.of());
    }
}
