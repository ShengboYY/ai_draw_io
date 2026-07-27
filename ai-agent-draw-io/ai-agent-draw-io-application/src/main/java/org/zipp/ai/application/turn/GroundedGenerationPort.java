package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;
import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Fixed grounded drawing seam consuming only a prepared Evidence capability. */
@FunctionalInterface
public interface GroundedGenerationPort {

    Result generate(Request request, CancellationSignal cancellation);

    /** Compatibility entry point for callers without attempt-scoped cancellation. */
    default Result generate(Request request) {
        return generate(request, CancellationSignal.NEVER);
    }

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            BoundSourcePlan plan,
            String preparedEvidenceRef,
            boolean includeCanvasContext
    ) {
        public Request {
            if (attempt == null || context == null || readSet == null || plan == null
                    || preparedEvidenceRef == null || preparedEvidenceRef.isBlank()) {
                throw new IllegalArgumentException("Grounded generation values must not be blank");
            }
        }

        /** Compatibility entry point for source requests unrelated to the current Canvas. */
        public Request(
                FencedAttempt attempt,
                BaseTurnContext context,
                ContextReadSet readSet,
                BoundSourcePlan plan,
                String preparedEvidenceRef
        ) {
            this(attempt, context, readSet, plan, preparedEvidenceRef, false);
        }
    }

    record Result(
            String payloadRef,
            String canvasXml,
            String assistantMessage,
            String citationManifestRef
    ) {
        public Result {
            ContractValues.requiredText(payloadRef, "payloadRef");
            ContractValues.requiredText(canvasXml, "canvasXml");
            ContractValues.requiredText(assistantMessage, "assistantMessage");
            ContractValues.requiredText(citationManifestRef, "citationManifestRef");
        }
    }
}
