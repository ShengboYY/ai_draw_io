package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

/** Evidence-only answer seam; aiKnowledgeAllowed is structurally fixed to false. */
@FunctionalInterface
public interface EvidenceAnswerGenerationPort {

    Result generate(Request request);

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourcePlanIdentity planIdentity,
            String preparedEvidenceRef
    ) {
        public Request {
            if (attempt == null || context == null || readSet == null || planIdentity == null
                    || preparedEvidenceRef == null || preparedEvidenceRef.isBlank()) {
                throw new IllegalArgumentException("Evidence answer values must not be blank");
            }
        }

        public boolean aiKnowledgeAllowed() {
            return false;
        }
    }

    record Result(
            String payloadRef,
            String assistantMessage,
            String claimManifestRef
    ) {
        public Result {
            ContractValues.requiredText(payloadRef, "payloadRef");
            ContractValues.requiredText(assistantMessage, "assistantMessage");
            ContractValues.requiredText(claimManifestRef, "claimManifestRef");
        }
    }
}
