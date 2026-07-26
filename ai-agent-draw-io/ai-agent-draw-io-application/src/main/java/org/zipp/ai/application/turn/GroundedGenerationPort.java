package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;

/** Fixed grounded drawing seam consuming only a prepared Evidence capability. */
@FunctionalInterface
public interface GroundedGenerationPort {

    Result generate(Request request);

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            BoundSourcePlan plan,
            String preparedEvidenceRef
    ) {
        public Request {
            if (attempt == null || context == null || readSet == null || plan == null
                    || preparedEvidenceRef == null || preparedEvidenceRef.isBlank()) {
                throw new IllegalArgumentException("Grounded generation values must not be blank");
            }
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
