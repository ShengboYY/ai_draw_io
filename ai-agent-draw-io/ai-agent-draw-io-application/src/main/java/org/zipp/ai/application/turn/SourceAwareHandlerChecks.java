package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

/** Shared invariant checks only; it performs no routing, source access, or policy decisions. */
final class SourceAwareHandlerChecks {

    private SourceAwareHandlerChecks() {
    }

    static void requireCommon(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourcePlanIdentity planIdentity,
            SourceCommitBinding sourceBinding
    ) {
        if (attempt == null || context == null || readSet == null
                || planIdentity == null || sourceBinding == null) {
            throw new IllegalArgumentException("source-aware handler values must not be null");
        }
        if (!attempt.key().turnId().equals(context.request().turnId())) {
            throw new IllegalArgumentException("SOURCE_CONTEXT_TURN_MISMATCH");
        }
        if (readSet.messageHighWater() != attempt.contextMessageHighWater()) {
            throw new IllegalArgumentException("SOURCE_CONTEXT_HIGH_WATER_MISMATCH");
        }
        if (!planIdentity.equals(sourceBinding.planIdentity())) {
            throw new IllegalArgumentException("SOURCE_PLAN_IDENTITY_MISMATCH");
        }
    }
}
