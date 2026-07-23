package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;

import java.util.concurrent.CompletionStage;

/** Single application-facing seam for preparing and atomically committing a direct image. */
public interface DirectImageConversionExecutionModule {
    CompletionStage<DirectImageConversionOutcome> execute(
            DirectImageConversionCommand command,
            EvidenceProgressListener progress,
            CancellationSignal cancellation);
}
