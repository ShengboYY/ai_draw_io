package org.zipp.ai.trigger.http.turn;

import org.springframework.http.HttpStatus;
import org.zipp.ai.application.turn.TurnSubmission;

import java.util.Objects;

/**
 * Maps typed turn submission outcomes to transport status without changing the application
 * outcome or treating a disconnected subscriber as cancellation.
 */
public final class TurnHttpSubmissionMapper {

    public TurnHttpSubmissionResult map(TurnSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        if (submission instanceof TurnSubmission.ExecutionAccepted
                || submission instanceof TurnSubmission.AlreadyRunning) {
            // RUNNING is durable; the client follows the status endpoint instead of replaying events.
            return new TurnHttpSubmissionResult(HttpStatus.ACCEPTED, submission, true);
        }
        if (submission instanceof TurnSubmission.TerminalReplay) {
            return new TurnHttpSubmissionResult(HttpStatus.OK, submission, false);
        }
        if (submission instanceof TurnSubmission.LegacyRetryExpired) {
            return new TurnHttpSubmissionResult(HttpStatus.GONE, submission, false);
        }
        if (submission instanceof TurnSubmission.LegacyHandoff) {
            return new TurnHttpSubmissionResult(HttpStatus.CONFLICT, submission, false);
        }
        if (submission instanceof TurnSubmission.IdempotencyConflict
                || submission instanceof TurnSubmission.AdmissionRejected) {
            return new TurnHttpSubmissionResult(HttpStatus.CONFLICT, submission, false);
        }
        if (submission instanceof TurnSubmission.TerminalUnavailable
                || submission instanceof TurnSubmission.NotReady) {
            return new TurnHttpSubmissionResult(HttpStatus.SERVICE_UNAVAILABLE, submission, false);
        }
        throw new IllegalStateException("unmapped turn submission: " + submission.getClass().getName());
    }
}
