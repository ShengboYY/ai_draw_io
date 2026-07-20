package org.zipp.ai.domain.retrieval;

import java.util.concurrent.CompletionStage;

public interface EvidencePreparationModule {
    CompletionStage<PreparationOutcome> prepare(EvidencePreparationCommand command,
                                                RunResourceDomain resources,
                                                EvidenceProgressListener progress,
                                                CancellationSignal cancellation);
}
