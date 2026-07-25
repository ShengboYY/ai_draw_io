package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnEngineMigrationCoordinatorTest {

    @Test
    void pausesBeforeSwitchAndResumesAfterSuccess() {
        List<String> calls = new ArrayList<>();
        AdmissionBarrier barrier = new RecordingBarrier(calls);
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                (expected, target) -> {
                    calls.add("switch:" + expected + "->" + target);
                    return new MigrationModeSwitchOutcome.Changed(
                            new MigrationStateSnapshot(1, target, Instant.parse("2026-07-26T00:00:00Z")));
                });

        MigrationModeSwitchOutcome outcome = coordinator.switchMode(TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY);

        assertEquals(MigrationModeSwitchOutcome.Changed.class, outcome.getClass());
        assertEquals(List.of("pause", "switch:LEGACY->V2_CANARY", "resume"), calls);
    }

    @Test
    void resumesWhenDurableSwitchFails() {
        RecordingBarrier barrier = new RecordingBarrier(new ArrayList<>());
        TurnEngineMigrationCoordinator coordinator = new TurnEngineMigrationCoordinator(
                barrier,
                (expected, target) -> {
                    throw new IllegalStateException("db unavailable");
                });

        assertThrows(IllegalStateException.class,
                () -> coordinator.switchMode(TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY));
        assertEquals(List.of("pause", "resume"), barrier.calls);
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
}
