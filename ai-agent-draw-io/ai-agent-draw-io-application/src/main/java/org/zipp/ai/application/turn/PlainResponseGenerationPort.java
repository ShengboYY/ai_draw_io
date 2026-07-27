package org.zipp.ai.application.turn;

import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Tool-free generation capability for source-free answer, review, and direct reply paths. */
@FunctionalInterface
public interface PlainResponseGenerationPort {

    PlainResponseGenerationResult generate(
            PlainResponseGenerationRequest request,
            TurnEventSink events,
            CancellationSignal cancellation
    );

    /** Compatibility entry point for callers without attempt-scoped cancellation. */
    default PlainResponseGenerationResult generate(
            PlainResponseGenerationRequest request,
            TurnEventSink events
    ) {
        return generate(request, events, CancellationSignal.NEVER);
    }
}
