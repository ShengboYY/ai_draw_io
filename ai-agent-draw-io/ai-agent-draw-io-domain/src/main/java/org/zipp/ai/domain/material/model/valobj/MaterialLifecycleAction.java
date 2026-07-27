package org.zipp.ai.domain.material.model.valobj;

/** Idempotent user or system lifecycle mutation. */
public enum MaterialLifecycleAction {
    PROMOTE,
    REMOVE,
    RESTORE,
    PERMANENT_DELETE,
    MEANINGFUL_ACTIVITY,
    TTL_EXPIRE,
    TRASH_EXPIRE,
    ACCOUNT_DELETE
}
