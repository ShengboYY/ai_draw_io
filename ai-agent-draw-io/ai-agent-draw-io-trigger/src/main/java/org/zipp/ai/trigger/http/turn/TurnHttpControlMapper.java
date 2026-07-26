package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Maps control-plane application outcomes to HTTP dispositions without changing their payloads.
 *
 * <p>Owner and not-found rejections intentionally share {@code 404} so the transport does not
 * reveal another owner's durable turn. A persisted cancellation is already complete, while a
 * terminal decoder failure remains retryable through the status endpoint.
 */
public final class TurnHttpControlMapper {

    public TurnHttpStatusResult mapStatus(TurnStatusQueryOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome instanceof TurnStatusQueryOutcome.Available) {
            return new TurnHttpStatusResult(HttpStatus.OK, outcome);
        }
        if (outcome instanceof TurnStatusQueryOutcome.TerminalUnavailable) {
            return new TurnHttpStatusResult(HttpStatus.SERVICE_UNAVAILABLE, outcome);
        }
        throw new IllegalStateException("unmapped turn status outcome: " + outcome.getClass().getName());
    }

    public TurnHttpCancelResult mapCancel(CancelTurnOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome instanceof CancelTurnOutcome.Cancelled
                || outcome instanceof CancelTurnOutcome.AlreadyTerminal) {
            return new TurnHttpCancelResult(HttpStatus.OK, outcome);
        }
        if (outcome instanceof CancelTurnOutcome.TerminalUnavailable) {
            return new TurnHttpCancelResult(HttpStatus.SERVICE_UNAVAILABLE, outcome);
        }
        if (outcome instanceof CancelTurnOutcome.FenceLost) {
            return new TurnHttpCancelResult(HttpStatus.CONFLICT, outcome);
        }
        if (outcome instanceof CancelTurnOutcome.Rejected rejected) {
            return new TurnHttpCancelResult(rejectedStatus(rejected.code()), outcome);
        }
        throw new IllegalStateException("unmapped turn cancellation outcome: "
                + outcome.getClass().getName());
    }

    private HttpStatus rejectedStatus(String code) {
        if ("OWNER_MISMATCH".equals(code) || "TURN_NOT_FOUND".equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("TURN_INSTANCE_NOT_READY".equals(code)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.CONFLICT;
    }
}
