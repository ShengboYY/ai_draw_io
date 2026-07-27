package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttemptWriteGateTest {

    @Test
    void disablingOneAttemptDoesNotDisableAReplacementEpoch() {
        AttemptWriteGate gate = new AttemptWriteGate();
        FencedAttempt first = attempt("attempt-1", 1);
        FencedAttempt replacement = attempt("attempt-2", 2);

        gate.disableAndDrain(first);

        TurnWriteGate.Permit replacementPermit = gate.tryEnter(replacement).orElseThrow();
        assertFalse(gate.tryEnter(first).isPresent());
        replacementPermit.close();
    }

    @Test
    void disableAndDrainWaitsForAnInFlightPermit() throws Exception {
        AttemptWriteGate gate = new AttemptWriteGate();
        FencedAttempt attempt = attempt("attempt-1", 1);
        TurnWriteGate.Permit permit = gate.tryEnter(attempt).orElseThrow();
        Thread drainer = new Thread(() -> gate.disableAndDrain(attempt));

        drainer.start();
        Thread.yield();
        assertTrue(drainer.isAlive());

        permit.close();
        drainer.join(1_000);

        assertFalse(drainer.isAlive());
        assertFalse(gate.tryEnter(attempt).isPresent());
    }

    private static FencedAttempt attempt(String attemptId, long epoch) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                AttemptLease.fromDatabaseClock(
                        attemptId, epoch, Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }
}
