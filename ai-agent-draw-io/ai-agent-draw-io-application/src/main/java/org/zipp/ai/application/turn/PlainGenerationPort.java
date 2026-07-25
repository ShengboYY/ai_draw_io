package org.zipp.ai.application.turn;

/** Tool-free model boundary for the isolated Plain path. */
@FunctionalInterface
public interface PlainGenerationPort {

    PlainGenerationResult generate(PlainGenerationRequest request, TurnEventSink events);
}
