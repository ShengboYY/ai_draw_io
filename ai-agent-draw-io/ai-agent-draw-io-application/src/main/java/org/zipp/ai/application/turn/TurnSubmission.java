package org.zipp.ai.application.turn;

public sealed interface TurnSubmission
        permits TurnSubmission.ExecutionAccepted,
        TurnSubmission.TerminalReplay,
        TurnSubmission.AlreadyRunning,
        TurnSubmission.IdempotencyConflict,
        TurnSubmission.LegacyRetryExpired,
        TurnSubmission.AdmissionRejected {

    record ExecutionAccepted(TurnKey key, FencedAttempt attempt) implements TurnSubmission {
    }

    record TerminalReplay(TurnKey key, PersistedTurnOutcome outcome) implements TurnSubmission {
    }

    record AlreadyRunning(TurnKey key, TurnStatusView status) implements TurnSubmission {
    }

    record IdempotencyConflict(TurnKey key, String code) implements TurnSubmission {
    }

    record LegacyRetryExpired(TurnKey key, String code) implements TurnSubmission {
    }

    record AdmissionRejected(TurnKey key, String code) implements TurnSubmission {
    }
}
