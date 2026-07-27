package org.zipp.ai.application.turn;

public sealed interface TurnSubmission
        permits TurnSubmission.ExecutionAccepted,
        TurnSubmission.TerminalReplay,
        TurnSubmission.TerminalUnavailable,
        TurnSubmission.AlreadyRunning,
        TurnSubmission.LegacyAssignmentPinned,
        TurnSubmission.IdempotencyConflict,
        TurnSubmission.LegacyRetryExpired,
        TurnSubmission.AdmissionRejected,
        TurnSubmission.NotReady {

    record ExecutionAccepted(TurnKey key, FencedAttempt attempt, LeaseTimingAnchor leaseTiming)
            implements TurnSubmission {

        public ExecutionAccepted {
            if (key == null || attempt == null || leaseTiming == null
                    || !attempt.key().equals(key)
                    || !attempt.lease().equals(leaseTiming.lease())) {
                throw new IllegalArgumentException("execution acceptance values must not be null");
            }
        }
    }

    record TerminalReplay(TurnKey key, PersistedTurnOutcome outcome) implements TurnSubmission {
    }

    record TerminalUnavailable(TurnKey key, TurnStatusView status, String code) implements TurnSubmission {

        public TerminalUnavailable {
            ContractValues.requiredText(code, "code");
        }
    }

    record AlreadyRunning(TurnKey key, TurnStatusView status) implements TurnSubmission {
    }

    /** A retry is pinned to a historical Legacy assignment and cannot be executed by V2. */
    record LegacyAssignmentPinned(TurnKey key) implements TurnSubmission {

        public LegacyAssignmentPinned {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
        }
    }

    record IdempotencyConflict(TurnKey key, String code) implements TurnSubmission {
    }

    record LegacyRetryExpired(TurnKey key, String code) implements TurnSubmission {
    }

    record AdmissionRejected(TurnKey key, String code) implements TurnSubmission {
    }

    /** Returned before conversation lookup so a paused instance cannot create new scope rows. */
    record NotReady(String code) implements TurnSubmission {

        public NotReady {
            ContractValues.requiredText(code, "code");
        }
    }
}
