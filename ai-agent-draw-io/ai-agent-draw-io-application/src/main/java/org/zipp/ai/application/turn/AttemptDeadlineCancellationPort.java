package org.zipp.ai.application.turn;

public interface AttemptDeadlineCancellationPort {

    DeadlineCancelOutcome cancel(FencedAttempt attempt, AttemptDeadlineReason reason);
}
