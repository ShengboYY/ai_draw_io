package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void pauseWaitsForAdmissionsThatAlreadyEntered() throws Exception {
        FakeLock lock = new FakeLock();
        TurnAdmissionGate gate = new TurnAdmissionGate(lock, ignored -> 0);
        InstanceBootId bootId = new InstanceBootId("boot-1");
        assertEquals(InstanceLockOutcome.ACQUIRED, gate.start(bootId));
        assertTrue(gate.tryEnter());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> drain = executor.submit(gate::pauseAndDrain);
            Thread.sleep(100);
            assertFalse(drain.isDone());
            gate.leave();
            drain.get(5, TimeUnit.SECONDS);
            assertFalse(gate.isOpen());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startupLockLossDuringReconciliationNeverOpensAdmission() {
        FakeLock lock = new FakeLock();
        TurnAdmissionGate gate = new TurnAdmissionGate(
                lock,
                ignored -> {
                    lock.held = false;
                    return 0;
                });

        assertEquals(InstanceLockOutcome.LOST, gate.start(new InstanceBootId("boot-1")));
        assertFalse(gate.isOpen());
    }

    @Test
    void failedRestartCannotReusePreviousBootIdentityDuringResume() {
        FakeLock lock = new FakeLock();
        TurnAdmissionGate gate = new TurnAdmissionGate(lock, ignored -> 0);
        InstanceBootId bootId = new InstanceBootId("boot-1");

        assertEquals(InstanceLockOutcome.ACQUIRED, gate.start(bootId));
        gate.pauseAndDrain();
        lock.nextAcquireOutcome = InstanceLockOutcome.ALREADY_HELD;

        assertEquals(InstanceLockOutcome.ALREADY_HELD, gate.start(bootId));
        assertFalse(gate.isOpen());
        assertThrows(IllegalStateException.class, gate::resume);
    }

    @Test
    void reconciliationFailureClearsBootIdentityBeforeRethrowing() {
        FakeLock lock = new FakeLock();
        TurnAdmissionGate gate = new TurnAdmissionGate(lock, ignored -> {
            throw new IllegalStateException("reconcile failed");
        });

        assertThrows(IllegalStateException.class,
                () -> gate.start(new InstanceBootId("boot-1")));
        assertFalse(gate.isOpen());
        assertThrows(IllegalStateException.class, gate::resume);
    }

    private static final class FakeLock implements SingleActiveInstanceLock {
        private boolean held;
        private InstanceLockOutcome nextAcquireOutcome = InstanceLockOutcome.ACQUIRED;

        @Override
        public InstanceLockOutcome acquire(InstanceBootId bootId) {
            if (nextAcquireOutcome != InstanceLockOutcome.ACQUIRED) {
                return nextAcquireOutcome;
            }
            held = true;
            return InstanceLockOutcome.ACQUIRED;
        }

        @Override
        public boolean isHeld(InstanceBootId bootId) {
            return held;
        }
    }
}
