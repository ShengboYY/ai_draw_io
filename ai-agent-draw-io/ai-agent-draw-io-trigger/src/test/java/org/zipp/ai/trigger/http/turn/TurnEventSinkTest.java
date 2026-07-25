package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnEventSinkTest {

    @Test
    void ndjsonWriterFailureDetachesWithoutThrowingOrCancelling() {
        List<String> lines = new ArrayList<>();
        NdjsonTurnEventSink sink = new NdjsonTurnEventSink(line -> {
            lines.add(line);
            throw new IllegalStateException("client disconnected");
        });

        sink.publish(new TurnEvent("plain_started", "CREATE", Instant.parse("2026-07-26T00:00:00Z")));
        sink.publish(new TurnEvent("plain_committed", "persisted", Instant.parse("2026-07-26T00:00:01Z")));

        assertTrue(sink.isDetached());
        assertEquals(1, lines.size());
    }

    @Test
    void bufferingSinkReturnsAnImmutableReceiptList() {
        BufferingTurnEventSink sink = new BufferingTurnEventSink();
        sink.publish(new TurnEvent("plain_started", "CREATE", Instant.parse("2026-07-26T00:00:00Z")));

        assertEquals(1, sink.events().size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> sink.events().clear());
    }
}
