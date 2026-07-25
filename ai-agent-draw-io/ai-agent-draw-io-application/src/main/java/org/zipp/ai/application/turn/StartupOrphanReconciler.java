package org.zipp.ai.application.turn;

/** Reconciles non-terminal executions from the previous boot before admission opens. */
public interface StartupOrphanReconciler {

    int reconcile(InstanceBootId currentBootId);
}
