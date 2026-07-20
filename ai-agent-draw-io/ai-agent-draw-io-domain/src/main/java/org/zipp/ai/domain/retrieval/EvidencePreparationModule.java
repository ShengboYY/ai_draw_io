package org.zipp.ai.domain.retrieval;

import java.util.concurrent.CompletionStage;

public interface EvidencePreparationModule {
    CompletionStage<PreparationOutcome> prepare(EvidencePreparationCommand command,
                                                RunResourceDomain resources,
                                                EvidenceProgressListener progress,
                                                CancellationSignal cancellation);

    /** Non-intrusive candidate-only observation; it must never hydrate evidence or hold read leases. */
    default CompletionStage<Void> observe(EvidencePreparationCommand command) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
