package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.concurrent.CompletionStage;

public interface DirectSourcePreparationModule {
    CompletionStage<DirectSourceOutcome> prepare(DirectSourceCommand command,
                                                 RunResourceDomain resources,
                                                 EvidenceProgressListener progress,
                                                 CancellationSignal cancellation);
}
