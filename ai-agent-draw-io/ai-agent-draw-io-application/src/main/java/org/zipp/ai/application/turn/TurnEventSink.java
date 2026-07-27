package org.zipp.ai.application.turn;

/** Delivery-only event sink; transport type must not select business behavior. */
@FunctionalInterface
public interface TurnEventSink {

    void publish(TurnEvent event);
}
