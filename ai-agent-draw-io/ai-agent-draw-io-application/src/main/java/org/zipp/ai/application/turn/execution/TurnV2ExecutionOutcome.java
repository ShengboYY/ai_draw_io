package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

/** Closed result for the isolated V2 executor; only source-free handlers reach this seam. */
public sealed interface TurnV2ExecutionOutcome
        permits TurnV2ExecutionOutcome.Committed,
        TurnV2ExecutionOutcome.NotDispatched,
        TurnV2ExecutionOutcome.PreparationBlocked {

    record Committed(FencedCommitOutcome outcome) implements TurnV2ExecutionOutcome {
        public Committed {
            if (outcome == null) {
                throw new IllegalArgumentException("V2 commit outcome must not be null");
            }
        }
    }

    record NotDispatched(TurnRouteDecision decision, String code) implements TurnV2ExecutionOutcome {
        public NotDispatched {
            if (decision == null || code == null || code.isBlank()) {
                throw new IllegalArgumentException("V2 non-dispatch outcome must be complete");
            }
        }
    }

    /** Preparation failure is returned without inventing a product terminal or calling a handler. */
    record PreparationBlocked(TurnV2PreHandlerOutcome outcome) implements TurnV2ExecutionOutcome {
        public PreparationBlocked {
            if (outcome == null || outcome instanceof TurnV2PreHandlerOutcome.Ready) {
                throw new IllegalArgumentException("V2 preparation must be blocked");
            }
        }
    }
}
