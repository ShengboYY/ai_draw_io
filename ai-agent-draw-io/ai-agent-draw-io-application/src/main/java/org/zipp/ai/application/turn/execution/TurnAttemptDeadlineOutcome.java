package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.DeadlineCancelOutcome;

/** Result of an attempt-scoped deadline callback before transport mapping. */
public sealed interface TurnAttemptDeadlineOutcome
        permits TurnAttemptDeadlineOutcome.Delegated,
        TurnAttemptDeadlineOutcome.WriteGateDisabled {

    record Delegated(DeadlineCancelOutcome outcome) implements TurnAttemptDeadlineOutcome {
        public Delegated {
            if (outcome == null) {
                throw new IllegalArgumentException("deadline outcome must not be null");
            }
        }
    }

    record WriteGateDisabled() implements TurnAttemptDeadlineOutcome {
    }
}
