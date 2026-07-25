package org.zipp.ai.application.turn;

import java.util.Objects;

/** Keeps turn admission closed until the serving instance owns the lock and repairs orphaned work. */
public final class TurnAdmissionGate implements AdmissionBarrier {

    private final SingleActiveInstanceLock instanceLock;
    private final StartupOrphanReconciler orphanReconciler;
    private InstanceBootId bootId;
    private boolean open;
    private int inFlight;

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
            // A failed restart must not leave a previous boot identity usable by resume().
            bootId = null;
            open = false;
            return lockOutcome;
        }
        try {
            // Admission opens only after the previous boot's durable RUNNING rows are reconciled.
            orphanReconciler.reconcile(requestedBootId);
            if (!instanceLock.isHeld(requestedBootId)) {
                // Reconciliation can outlive the dedicated DB connection; never open on a lost lock.
                bootId = null;
                open = false;
                return InstanceLockOutcome.LOST;
            }
            bootId = requestedBootId;
            open = true;
            return InstanceLockOutcome.ACQUIRED;
        } catch (RuntimeException exception) {
            bootId = null;
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
        if (!isReady()) {
            // A migration window must never run from a boot that does not own the singleton lock.
            throw new IllegalStateException("TURN_INSTANCE_NOT_READY");
        }
        // Close first, then wait for every admission that crossed the gate before the pause.
        open = false;
        while (inFlight > 0) {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TURN_ADMISSION_DRAIN_INTERRUPTED", exception);
            }
        }
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

    @Override
    public synchronized boolean tryEnter() {
        if (!isReady()) {
            return false;
        }
        inFlight++;
        return true;
    }

    @Override
    public synchronized void leave() {
        if (inFlight <= 0) {
            throw new IllegalStateException("TURN_ADMISSION_NOT_ENTERED");
        }
        inFlight--;
        if (inFlight == 0) {
            notifyAll();
        }
    }
}
