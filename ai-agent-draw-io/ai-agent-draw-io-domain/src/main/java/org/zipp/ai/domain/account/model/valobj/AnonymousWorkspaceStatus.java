package org.zipp.ai.domain.account.model.valobj;

/** Lifecycle of a server-issued anonymous workspace credential. */
public enum AnonymousWorkspaceStatus {
    ACTIVE,
    CLAIMED,
    REVOKED
}
