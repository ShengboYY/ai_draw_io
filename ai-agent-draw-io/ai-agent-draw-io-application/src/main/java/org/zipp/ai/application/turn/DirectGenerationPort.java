package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;

/** Fixed Direct drawing model seam; no dynamic tool registry is accepted. */
@FunctionalInterface
public interface DirectGenerationPort {

    Result generate(Request request);

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            BoundSourcePlan plan,
            DirectVisionPort.Observation observation
    ) {
        public Request {
            if (attempt == null || context == null || readSet == null
                    || plan == null || observation == null) {
                throw new IllegalArgumentException("Direct generation values must not be null");
            }
        }
    }

    record Result(
            String payloadRef,
            String canvasXml,
            String assistantMessage
    ) {
        public Result {
            ContractValues.requiredText(payloadRef, "payloadRef");
            ContractValues.requiredText(canvasXml, "canvasXml");
            ContractValues.requiredText(assistantMessage, "assistantMessage");
        }
    }
}
