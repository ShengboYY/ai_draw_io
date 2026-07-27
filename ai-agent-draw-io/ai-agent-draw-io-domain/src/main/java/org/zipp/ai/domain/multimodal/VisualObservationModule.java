package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.concurrent.CompletionStage;

public interface VisualObservationModule {
    CompletionStage<VisualObservationOutcome> observe(VisualObservationCommand command,
                                                      RunResourceDomain resources,
                                                      CancellationSignal cancellation);
}
