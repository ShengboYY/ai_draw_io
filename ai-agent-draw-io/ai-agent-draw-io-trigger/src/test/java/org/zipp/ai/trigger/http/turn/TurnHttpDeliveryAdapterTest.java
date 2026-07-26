package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnSubmission;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    private static TurnHttpRequest request() {
        return new TurnHttpRequest(
                "turn-1", "conversation:conversation-1", "diagram-1", "client-1", "draw it",
                null, List.of(), null, List.of());
    }
}
