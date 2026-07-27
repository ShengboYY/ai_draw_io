package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Sync delivery sink; it buffers only non-terminal progress events for response mapping. */
public final class BufferingTurnEventSink implements TurnEventSink {

    private final List<TurnEvent> events = new ArrayList<>();
    private boolean detached;

    @Override
    public synchronized void publish(TurnEvent event) {
        if (!detached) {
            events.add(Objects.requireNonNull(event, "event"));
        }
    }

    public synchronized List<TurnEvent> events() {
        return List.copyOf(events);
    }

    public synchronized void detach() {
        detached = true;
    }

    public synchronized boolean isDetached() {
        return detached;
    }
}
