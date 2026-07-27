package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnSubmission;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnHttpSubmissionMapperTest {

    private final TurnHttpSubmissionMapper mapper = new TurnHttpSubmissionMapper();
    private final TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
    private final TurnStatusView running = new TurnStatusView(
            key, TurnStatus.RUNNING, "attempt-1", 1, null, null,
            Instant.parse("2026-07-26T00:00:00Z"));
    private final PersistedTurnOutcome terminal =
            new PersistedTurnOutcome(TurnStatus.COMPLETED, "DONE", "plain", null, "{}");

    @Test
    void acceptedAndAlreadyRunningUseAcceptedWithStatusEndpoint() {
        TurnHttpSubmissionResult accepted = mapper.map(acceptedSubmission());
        TurnHttpSubmissionResult runningResult = mapper.map(new TurnSubmission.AlreadyRunning(key, running));

        assertEquals(HttpStatus.ACCEPTED, accepted.httpStatus());
        assertTrue(accepted.statusEndpointRequired());
        assertEquals(HttpStatus.ACCEPTED, runningResult.httpStatus());
        assertTrue(runningResult.statusEndpointRequired());
    }

    @Test
    void terminalReplayIsImmediateSuccessWithoutStatusEndpoint() {
        TurnHttpSubmissionResult result = mapper.map(new TurnSubmission.TerminalReplay(key, terminal));

        assertEquals(HttpStatus.OK, result.httpStatus());
        assertFalse(result.statusEndpointRequired());
    }

    @Test
    void retryExpiryConflictAndUnavailableRemainTypedTransportOutcomes() {
        assertEquals(HttpStatus.GONE,
                mapper.map(new TurnSubmission.LegacyRetryExpired(key, "EXPIRED_GONE")).httpStatus());
        assertEquals(HttpStatus.CONFLICT,
                mapper.map(new TurnSubmission.IdempotencyConflict(key, "FINGERPRINT_CONFLICT")).httpStatus());
        assertEquals(HttpStatus.CONFLICT,
                mapper.map(new TurnSubmission.AdmissionRejected(key, "CONVERSATION_NOT_ACTIVE")).httpStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                mapper.map(new TurnSubmission.TerminalUnavailable(
                        key, running, "TERMINAL_SCHEMA_UNAVAILABLE")).httpStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                mapper.map(new TurnSubmission.NotReady("TURN_INSTANCE_NOT_READY")).httpStatus());
    }

    @Test
    void syncDeliveryResultExposesMappingButKeepsSubmissionAndDetachSeparate() {
        TurnHttpDeliveryResult result = new TurnHttpDeliveryResult(
                new TurnSubmission.TerminalReplay(key, terminal), java.util.List.of(), true);

        assertEquals(HttpStatus.OK, result.responseStatus());
        assertFalse(result.statusEndpointRequired());
        assertTrue(result.detached());
    }

    private TurnSubmission.ExecutionAccepted acceptedSubmission() {
        FencedAttempt attempt = new FencedAttempt(
                key,
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                1,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
        return new TurnSubmission.ExecutionAccepted(
                key, attempt, new LeaseTimingAnchor(System.nanoTime(), attempt.lease()));
    }
}
