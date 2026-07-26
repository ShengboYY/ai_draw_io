package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;

/** Tool-free visual observation seam; it cannot query Retrieval. */
@FunctionalInterface
public interface DirectVisionPort {

    Observation observe(Request request);

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            BoundSourcePlan plan,
            String artifactLeaseRef
    ) {
        public Request {
            if (attempt == null || plan == null
                    || artifactLeaseRef == null || artifactLeaseRef.isBlank()) {
                throw new IllegalArgumentException("Direct vision request values must not be blank");
            }
        }

        /** Compatibility constructor for projection-only tests that do not need request context. */
        public Request(FencedAttempt attempt, BoundSourcePlan plan, String artifactLeaseRef) {
            this(attempt, null, plan, artifactLeaseRef);
        }
    }

    record Observation(
            String observationRef,
            String observationFingerprint
    ) {
        public Observation {
            ContractValues.requiredText(observationRef, "observationRef");
            ContractValues.requiredText(observationFingerprint, "observationFingerprint");
        }

        public Observation(String observationRef) {
            this(observationRef, observationRef);
        }
    }
}
