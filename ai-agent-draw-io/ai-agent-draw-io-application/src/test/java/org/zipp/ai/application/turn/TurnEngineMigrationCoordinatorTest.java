package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnEngineMigrationCoordinatorTest {

    @Test
    void pausesBeforeSwitchAndResumesAfterSuccess() {
        List<String> calls = new ArrayList<>();
        AdmissionBarrier barrier = new RecordingBarrier(calls);
        RecordingExpiry expiry = new RecordingExpiry(calls, 0);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                (expected, target) -> {
                    calls.add("switch:" + expected + "->" + target);
                    return new MigrationModeSwitchOutcome.Changed(
                        new MigrationStateSnapshot(1, target, Instant.parse("2026-07-26T00:00:00Z")));
                }, expiry);

        MigrationModeSwitchOutcome outcome = coordinator.switchMode(TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY);

        assertEquals(MigrationModeSwitchOutcome.Changed.class, outcome.getClass());
        assertEquals(List.of(
                "pause", "backfill:100", "expire:100", "switch:LEGACY->V2_CANARY", "resume"), calls);
    }

    @Test
    void drainsAllBackfillAndExpiryBatchesBeforeSwitch() {
        List<String> calls = new ArrayList<>();
        SequencedExpiry expiry = new SequencedExpiry(calls, new int[]{2, 0}, new int[]{1, 0});
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                new RecordingBarrier(calls),
                (expected, target) -> {
                    calls.add("switch:" + expected + "->" + target);
                    return new MigrationModeSwitchOutcome.Changed(
                            new MigrationStateSnapshot(1, target, Instant.parse("2026-07-26T00:00:00Z")));
                },
                expiry);

        coordinator.switchMode(TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY);

        assertEquals(List.of(
                "pause", "backfill:100", "backfill:100",
                "expire:100", "expire:100", "switch:LEGACY->V2_CANARY", "resume"), calls);
    }

    @Test
    void resumesWhenDurableSwitchFails() {
        List<String> calls = new ArrayList<>();
        RecordingBarrier barrier = new RecordingBarrier(calls);
        RecordingExpiry expiry = new RecordingExpiry(calls, 0);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                (expected, target) -> {
                    throw new IllegalStateException("db unavailable");
                }, expiry);

        assertThrows(IllegalStateException.class,
                () -> coordinator.switchMode(TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY));
        assertEquals(List.of("pause", "backfill:100", "expire:100", "resume"), barrier.calls);
    }

    @Test
    void backfillFailureDoesNotAttemptModeSwitch() {
        List<String> calls = new ArrayList<>();
        RecordingBarrier barrier = new RecordingBarrier(calls);
        boolean[] switched = {false};
        LegacyRetryExpiryPort expiry = new LegacyRetryExpiryPort() {
            @Override
            public int backfillRetryable(int batchSize) {
                calls.add("backfill:" + batchSize);
                throw new IllegalStateException("backfill unavailable");
            }

            @Override
            public int expireDue(int batchSize) {
                calls.add("expire:" + batchSize);
                return 0;
            }
        };
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                (expected, target) -> {
                    switched[0] = true;
                    return new MigrationModeSwitchOutcome.Rejected("UNEXPECTED");
                },
                expiry);

        assertThrows(IllegalStateException.class,
                () -> coordinator.switchMode(TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY));
        assertFalse(switched[0]);
        assertEquals(List.of("pause", "backfill:100", "resume"), calls);
    }

    @Test
    void pausesAroundExpiryScannerAndResumesAfterCompletion() {
        List<String> calls = new ArrayList<>();
        RecordingExpiry expiry = new RecordingExpiry(calls, 3);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                new RecordingBarrier(calls),
                (expected, target) -> new MigrationModeSwitchOutcome.AlreadyAtTarget(
                        new MigrationStateSnapshot(1, expected, Instant.parse("2026-07-26T00:00:00Z"))),
                expiry);

        assertEquals(3, coordinator.expireLegacyRetries(25));
        assertEquals(List.of("pause", "expire:25", "resume"), calls);
    }

    private static final class RecordingBarrier implements AdmissionBarrier {
        private final List<String> calls;

        private RecordingBarrier(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void pauseAndDrain() {
            calls.add("pause");
        }

        @Override
        public void resume() {
            calls.add("resume");
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class RecordingExpiry implements LegacyRetryExpiryPort {
        private final List<String> calls;
        private final int expireResult;

        private RecordingExpiry(List<String> calls, int expireResult) {
            this.calls = calls;
            this.expireResult = expireResult;
        }

        @Override
        public int backfillRetryable(int batchSize) {
            calls.add("backfill:" + batchSize);
            return 0;
        }

        @Override
        public int expireDue(int batchSize) {
            calls.add("expire:" + batchSize);
            return expireResult;
        }
    }

    private static final class SequencedExpiry implements LegacyRetryExpiryPort {
        private final List<String> calls;
        private final int[] backfillResults;
        private final int[] expiryResults;
        private int backfillIndex;
        private int expiryIndex;

        private SequencedExpiry(List<String> calls, int[] backfillResults, int[] expiryResults) {
            this.calls = calls;
            this.backfillResults = backfillResults;
            this.expiryResults = expiryResults;
        }

        @Override
        public int backfillRetryable(int batchSize) {
            calls.add("backfill:" + batchSize);
            return backfillResults[Math.min(backfillIndex++, backfillResults.length - 1)];
        }

        @Override
        public int expireDue(int batchSize) {
            calls.add("expire:" + batchSize);
            return expiryResults[Math.min(expiryIndex++, expiryResults.length - 1)];
        }
    }
}
