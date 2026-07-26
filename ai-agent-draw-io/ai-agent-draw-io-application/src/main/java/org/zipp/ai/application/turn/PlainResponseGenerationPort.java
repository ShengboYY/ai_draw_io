package org.zipp.ai.application.turn;

/** Tool-free generation capability for source-free answer, review, and direct reply paths. */
public interface PlainResponseGenerationPort {

    PlainResponseGenerationResult generate(
            PlainResponseGenerationRequest request,
            TurnEventSink events
    );
}
