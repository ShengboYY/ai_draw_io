package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnStatusView;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurnHttpControlMapperTest {

    private final TurnHttpControlMapper mapper = new TurnHttpControlMapper();
    private final TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
    private final TurnStatusView status = new TurnStatusView(
            key, TurnStatus.RUNNING, "attempt-1", 1, null, null,
            Instant.parse("2026-07-26T00:00:00Z"));

    @Test
    void statusMapsAvailableAndDecoderUnavailableSeparately() {
        assertEquals(HttpStatus.OK, mapper.mapStatus(
                new TurnStatusQueryOutcome.Available(status)).httpStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, mapper.mapStatus(
                new TurnStatusQueryOutcome.TerminalUnavailable(status, "TERMINAL_PAYLOAD_UNAVAILABLE"))
                .httpStatus());
    }

    @Test
    void cancellationMapsDurableWinnersAndRetryableFailures() {
        PersistedTurnOutcome cancelled = terminal(TurnStatus.CANCELLED, "CANCELLED");

        assertEquals(HttpStatus.OK, mapper.mapCancel(new CancelTurnOutcome.Cancelled(cancelled))
                .httpStatus());
        assertEquals(HttpStatus.OK, mapper.mapCancel(new CancelTurnOutcome.AlreadyTerminal(cancelled))
                .httpStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, mapper.mapCancel(
                new CancelTurnOutcome.TerminalUnavailable(status, "TERMINAL_PAYLOAD_UNAVAILABLE"))
                .httpStatus());
        assertEquals(HttpStatus.CONFLICT, mapper.mapCancel(new CancelTurnOutcome.FenceLost(status))
                .httpStatus());
    }

    @Test
    void cancellationRejectionsDoNotRevealOwnerOrReadinessDetails() {
        assertEquals(HttpStatus.NOT_FOUND, mapper.mapCancel(
                new CancelTurnOutcome.Rejected("OWNER_MISMATCH")).httpStatus());
        assertEquals(HttpStatus.NOT_FOUND, mapper.mapCancel(
                new CancelTurnOutcome.Rejected("TURN_NOT_FOUND")).httpStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, mapper.mapCancel(
                new CancelTurnOutcome.Rejected("TURN_INSTANCE_NOT_READY")).httpStatus());
        assertEquals(HttpStatus.CONFLICT, mapper.mapCancel(
                new CancelTurnOutcome.Rejected("CANCEL_RACE_LOST")).httpStatus());
    }

    private PersistedTurnOutcome terminal(TurnStatus terminalStatus, String code) {
        return new PersistedTurnOutcome(terminalStatus, code, "application/json", null, null);
    }
}
