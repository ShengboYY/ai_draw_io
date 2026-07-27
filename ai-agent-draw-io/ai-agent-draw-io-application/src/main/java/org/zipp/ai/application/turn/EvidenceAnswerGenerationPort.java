package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;
import org.zipp.ai.domain.retrieval.CancellationSignal;

/** Evidence-only answer seam; aiKnowledgeAllowed is structurally fixed to false. */
@FunctionalInterface
public interface EvidenceAnswerGenerationPort {

    Result generate(Request request, CancellationSignal cancellation);

    /** Compatibility entry point for callers without attempt-scoped cancellation. */
    default Result generate(Request request) {
        return generate(request, CancellationSignal.NEVER);
    }

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourcePlanIdentity planIdentity,
            String preparedEvidenceRef,
            boolean includeCanvasContext
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

        /** Compatibility entry point for evidence answers unrelated to the current Canvas. */
        public Request(
                FencedAttempt attempt,
                BaseTurnContext context,
                ContextReadSet readSet,
                SourcePlanIdentity planIdentity,
                String preparedEvidenceRef
        ) {
            this(attempt, context, readSet, planIdentity, preparedEvidenceRef, false);
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
