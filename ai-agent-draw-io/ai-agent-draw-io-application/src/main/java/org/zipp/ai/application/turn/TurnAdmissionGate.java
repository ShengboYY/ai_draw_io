package org.zipp.ai.application.turn;

import java.util.Objects;

/** Keeps turn admission closed until the serving instance owns the lock and repairs orphaned work. */
public final class TurnAdmissionGate implements AdmissionBarrier {

    private final SingleActiveInstanceLock instanceLock;
    private final StartupOrphanReconciler orphanReconciler;
    private InstanceBootId bootId;
    private boolean open;

    public TurnAdmissionGate(
            SingleActiveInstanceLock instanceLock,
            StartupOrphanReconciler orphanReconciler
    ) {
        this.instanceLock = Objects.requireNonNull(instanceLock, "instanceLock");
        this.orphanReconciler = Objects.requireNonNull(orphanReconciler, "orphanReconciler");
    }

    public synchronized InstanceLockOutcome start(InstanceBootId requestedBootId) {
        Objects.requireNonNull(requestedBootId, "requestedBootId");
        if (requestedBootId.equals(bootId) && open && instanceLock.isHeld(requestedBootId)) {
            return InstanceLockOutcome.ACQUIRED;
        }
        InstanceLockOutcome lockOutcome = instanceLock.acquire(requestedBootId);
        if (lockOutcome != InstanceLockOutcome.ACQUIRED) {
            open = false;
            return lockOutcome;
        }
        try {
            // Admission opens only after the previous boot's durable RUNNING rows are reconciled.
            orphanReconciler.reconcile(requestedBootId);
            bootId = requestedBootId;
            open = true;
            return InstanceLockOutcome.ACQUIRED;
        } catch (RuntimeException exception) {
            open = false;
            throw exception;
        }
    }

    public synchronized boolean isReady() {
        if (!open || bootId == null || !instanceLock.isHeld(bootId)) {
            open = false;
            return false;
        }
        return true;
    }

    @Override
    public synchronized void pauseAndDrain() {
        // M1 has no local dispatch queue; closing the gate prevents new admission while durable work drains.
        open = false;
    }

    @Override
    public synchronized void resume() {
        if (bootId == null || !instanceLock.isHeld(bootId)) {
            open = false;
            throw new IllegalStateException("TURN_INSTANCE_LOCK_LOST");
        }
        open = true;
    }

    @Override
    public synchronized boolean isOpen() {
        return isReady();
    }
}
