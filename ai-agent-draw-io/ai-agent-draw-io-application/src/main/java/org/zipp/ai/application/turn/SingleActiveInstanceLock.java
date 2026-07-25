package org.zipp.ai.application.turn;

/** Single-active-instance guard; the implementation holds a dedicated DB session lock. */
public interface SingleActiveInstanceLock {

    InstanceLockOutcome acquire(InstanceBootId bootId);

    boolean isHeld(InstanceBootId bootId);
}
