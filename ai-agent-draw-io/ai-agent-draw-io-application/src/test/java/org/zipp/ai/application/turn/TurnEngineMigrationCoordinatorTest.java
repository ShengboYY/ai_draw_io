package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnEngineMigrationCoordinatorTest {

    @Test
    void rejectsCanaryUntilStableCohortSelectionExists() {
        List<String> calls = new ArrayList<>();
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                new RecordingBarrier(calls),
                command -> {
                    throw new AssertionError("canary must not reach durable switch");
                },
                new RecordingExpiry(calls, 0));

        MigrationModeSwitchOutcome outcome = coordinator.switchMode(
                new MigrationStateSnapshot(0, TurnEngineMode.LEGACY,
                        Instant.parse("2026-07-26T00:00:00Z")),
                TurnEngineMode.V2_CANARY);

        MigrationModeSwitchOutcome.Rejected rejected =
                assertInstanceOf(MigrationModeSwitchOutcome.Rejected.class, outcome);
        assertEquals("V2_CANARY_UNSUPPORTED", rejected.code());
        assertEquals(List.of(), calls);
    }

    @Test
    void pausesBeforeSwitchAndResumesAfterSuccess() {
        List<String> calls = new ArrayList<>();
        AdmissionBarrier barrier = new RecordingBarrier(calls);
        RecordingExpiry expiry = new RecordingExpiry(calls, 0);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                command -> {
                    assertEquals(0, command.expectedGeneration());
                    calls.add("switch:" + command.expectedMode() + "->" + command.targetMode());
                    return new MigrationModeSwitchOutcome.Changed(
                        new MigrationStateSnapshot(1, command.targetMode(), Instant.parse("2026-07-26T00:00:00Z")));
                }, expiry);

        MigrationModeSwitchOutcome outcome = coordinator.switchMode(
                new MigrationStateSnapshot(0, TurnEngineMode.LEGACY,
                        Instant.parse("2026-07-26T00:00:00Z")),
                TurnEngineMode.ALL_V2);

        assertEquals(MigrationModeSwitchOutcome.Changed.class, outcome.getClass());
        assertEquals(List.of(
                "pause", "backfill:100", "expire:100", "switch:LEGACY->ALL_V2", "resume"), calls);
    }

    @Test
    void drainsAllBackfillAndExpiryBatchesBeforeSwitch() {
        List<String> calls = new ArrayList<>();
        SequencedExpiry expiry = new SequencedExpiry(calls, new int[]{2, 0}, new int[]{1, 0});
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                new RecordingBarrier(calls),
                command -> {
                    calls.add("switch:" + command.expectedMode() + "->" + command.targetMode());
                    return new MigrationModeSwitchOutcome.Changed(
                            new MigrationStateSnapshot(1, command.targetMode(),
                                    Instant.parse("2026-07-26T00:00:00Z")));
                },
                expiry);

        coordinator.switchMode(
                new MigrationStateSnapshot(0, TurnEngineMode.LEGACY,
                        Instant.parse("2026-07-26T00:00:00Z")),
                TurnEngineMode.ALL_V2);

        assertEquals(List.of(
                "pause", "backfill:100", "backfill:100",
                "expire:100", "expire:100", "switch:LEGACY->ALL_V2", "resume"), calls);
    }

    @Test
    void resumesWhenDurableSwitchFails() {
        List<String> calls = new ArrayList<>();
        RecordingBarrier barrier = new RecordingBarrier(calls);
        RecordingExpiry expiry = new RecordingExpiry(calls, 0);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                command -> {
                    throw new IllegalStateException("db unavailable");
                }, expiry);

        assertThrows(IllegalStateException.class,
                () -> coordinator.switchMode(
                        new MigrationStateSnapshot(0, TurnEngineMode.LEGACY,
                                Instant.parse("2026-07-26T00:00:00Z")),
                        TurnEngineMode.ALL_V2));
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
                command -> {
                    switched[0] = true;
                    return new MigrationModeSwitchOutcome.Rejected("UNEXPECTED");
                },
                expiry);

        assertThrows(IllegalStateException.class,
                () -> coordinator.switchMode(
                        new MigrationStateSnapshot(0, TurnEngineMode.LEGACY,
                                Instant.parse("2026-07-26T00:00:00Z")),
                        TurnEngineMode.ALL_V2));
        assertFalse(switched[0]);
        assertEquals(List.of("pause", "backfill:100", "resume"), calls);
    }

    @Test
    void pausesAroundExpiryScannerAndResumesAfterCompletion() {
        List<String> calls = new ArrayList<>();
        RecordingExpiry expiry = new RecordingExpiry(calls, 3);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                new RecordingBarrier(calls),
                command -> new MigrationModeSwitchOutcome.AlreadyAtTarget(
                        new MigrationStateSnapshot(1, command.expectedMode(),
                                Instant.parse("2026-07-26T00:00:00Z"))),
                expiry);

        assertEquals(3, coordinator.expireLegacyRetries(25));
        assertEquals(List.of("pause", "expire:25", "resume"), calls);
    }

    @Test
    void serializesMigrationOperationsBeforeEitherCanResumeAdmission() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstBackfillEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBackfill = new CountDownLatch(1);
        LegacyRetryExpiryPort expiry = new LegacyRetryExpiryPort() {
            @Override
            public int backfillRetryable(int batchSize) {
                calls.add("backfill:" + batchSize);
                firstBackfillEntered.countDown();
                await(releaseFirstBackfill);
                return 0;
            }

            @Override
            public int expireDue(int batchSize) {
                calls.add("expire:" + batchSize);
                return 0;
            }
        };
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                new RecordingBarrier(calls),
                command -> {
                    calls.add("switch:" + command.expectedMode() + "->" + command.targetMode());
                    return new MigrationModeSwitchOutcome.Changed(
                            new MigrationStateSnapshot(1, command.targetMode(),
                                    Instant.parse("2026-07-26T00:00:00Z")));
                },
                expiry);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MigrationModeSwitchOutcome> switchFuture = executor.submit(() -> coordinator.switchMode(
                    new MigrationStateSnapshot(0, TurnEngineMode.LEGACY,
                            Instant.parse("2026-07-26T00:00:00Z")),
                    TurnEngineMode.ALL_V2));
            assertTrue(firstBackfillEntered.await(5, TimeUnit.SECONDS));
            Future<Integer> expiryFuture = executor.submit(() -> coordinator.expireLegacyRetries(25));

            // The second operation cannot resume the gate while the first drain is active.
            assertFalse(expiryFuture.isDone());
            releaseFirstBackfill.countDown();

            switchFuture.get(5, TimeUnit.SECONDS);
            expiryFuture.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstBackfill.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(
                "pause", "backfill:100", "expire:100", "switch:LEGACY->ALL_V2", "resume",
                "pause", "expire:25", "resume"), calls);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for migration test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("migration test interrupted", exception);
        }
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
