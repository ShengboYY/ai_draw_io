package org.zipp.ai.trigger.http.turn;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;

import java.util.Objects;

/**
 * Detachable NDJSON delivery sink. A writer failure detaches the subscriber and is never
 * propagated as a business terminal or cancellation.
 */
public final class NdjsonTurnEventSink implements TurnEventSink {

    private final NdjsonLineWriter writer;
    private boolean detached;

    public NdjsonTurnEventSink(NdjsonLineWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public synchronized void publish(TurnEvent event) {
        if (detached) {
            return;
        }
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", event.type());
            envelope.put("payload", event.payload());
            envelope.put("occurredAt", event.occurredAt().toString());
            writer.write(JSON.toJSONString(envelope) + "\n");
        } catch (Exception deliveryFailure) {
            // The execution may still finish and be queried through the durable status endpoint.
            detached = true;
        }
    }

    public synchronized void detach() {
        detached = true;
    }

    public synchronized boolean isDetached() {
        return detached;
    }
}
