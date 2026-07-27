package org.zipp.ai.application.turn;

import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Tool-free model boundary for the isolated Plain path. */
@FunctionalInterface
public interface PlainGenerationPort {

    PlainGenerationResult generate(
            PlainGenerationRequest request,
            TurnEventSink events,
            CancellationSignal cancellation);

    /** Compatibility entry point for callers without attempt-scoped cancellation. */
    default PlainGenerationResult generate(PlainGenerationRequest request, TurnEventSink events) {
        return generate(request, events, CancellationSignal.NEVER);
    }
}
