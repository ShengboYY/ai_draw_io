package org.zipp.ai.infrastructure.turn.model;

import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.DirectGenerationPort;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectPreparationStore;

import java.util.concurrent.CancellationException;

/** Returns the already verified Direct projection without asking a second model to rewrite it. */
@Component
public final class PreparedDirectGenerationAdapter implements DirectGenerationPort {

    private final MySqlDirectPreparationStore preparations;

    public PreparedDirectGenerationAdapter(MySqlDirectPreparationStore preparations) {
        this.preparations = preparations;
    }

    @Override
    public Result generate(Request request, CancellationSignal cancellation) {
        if (request == null) throw new IllegalArgumentException("direct generation request is required");
        if (cancellation != null && cancellation.isCancelled()) {
            throw new CancellationException("DIRECT_GENERATION_CANCELLED");
        }
        MySqlDirectPreparationStore.Prepared prepared = preparations.find(
                        request.attempt(), request.observation().observationRef())
                .orElseThrow(() -> new IllegalStateException("DIRECT_PREPARATION_NOT_FOUND"));
        if (!prepared.planFingerprint().equals(request.plan().identity().planFingerprint())
                || !prepared.observationFingerprint().equals(request.observation().observationFingerprint())) {
            throw new IllegalStateException("DIRECT_PREPARATION_BINDING_MISMATCH");
        }
        if (cancellation != null && cancellation.isCancelled()) {
            throw new CancellationException("DIRECT_GENERATION_CANCELLED");
        }
        // The preparation stage already performs canonical topology and safe-XML verification.
        return new Result(
                prepared.preparedRef(),
                prepared.canvasXml(),
                "Reconstructed the attached image as an editable diagram. "
                        + "Please review any unclear text or connectors.");
    }
}
