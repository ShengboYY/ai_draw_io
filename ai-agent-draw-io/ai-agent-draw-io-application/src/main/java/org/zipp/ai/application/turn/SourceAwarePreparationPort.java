package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.SourcePlanDecision;
import org.zipp.ai.application.turn.planning.SourceProbeCommand;

/**
 * Probe-to-capability boundary. Implementations resolve the immutable source snapshot, prepare
 * the exact artifact/evidence capability, and must not expose raw source content to the planner.
 */
@FunctionalInterface
public interface SourceAwarePreparationPort {

    Outcome prepare(Request request);

    record Request(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourceProbeCommand probeCommand,
            SourcePlanDecision plan
    ) {
        public Request {
            if (attempt == null || context == null || readSet == null
                    || probeCommand == null || plan == null) {
                throw new IllegalArgumentException("source preparation values must not be null");
            }
        }
    }

    sealed interface Outcome permits Outcome.Ready, Outcome.Rejected {
        record Ready(SourceAwarePreparedExecution execution) implements Outcome {
            public Ready {
                if (execution == null) {
                    throw new IllegalArgumentException("prepared execution must not be null");
                }
            }
        }

        record Rejected(String code) implements Outcome {
            public Rejected {
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("source preparation rejection must not be blank");
                }
            }
        }
    }
}
