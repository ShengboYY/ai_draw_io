package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnAdmissionGateTest {

    @Test
    void startupReconcilesBeforeOpeningAndPauseRequiresLockToResume() {
        FakeLock lock = new FakeLock();
        int[] reconciliations = {0};
        TurnAdmissionGate gate = new TurnAdmissionGate(
                lock,
                bootId -> {
                    // The gate has not opened yet, but the instance lock must already be held.
                    assertTrue(lock.isHeld(bootId));
                    reconciliations[0]++;
                    return 1;
                });
        InstanceBootId bootId = new InstanceBootId("boot-1");

        assertEquals(InstanceLockOutcome.ACQUIRED, gate.start(bootId));
        assertEquals(1, reconciliations[0]);
        assertTrue(gate.isReady());
        gate.pauseAndDrain();
        assertFalse(gate.isOpen());
        gate.resume();
        assertTrue(gate.isOpen());
        lock.held = false;
        assertFalse(gate.isReady());
    }

    private static final class FakeLock implements SingleActiveInstanceLock {
        private boolean held;

        @Override
        public InstanceLockOutcome acquire(InstanceBootId bootId) {
            held = true;
            return InstanceLockOutcome.ACQUIRED;
        }

        @Override
        public boolean isHeld(InstanceBootId bootId) {
            return held;
        }
    }
}
