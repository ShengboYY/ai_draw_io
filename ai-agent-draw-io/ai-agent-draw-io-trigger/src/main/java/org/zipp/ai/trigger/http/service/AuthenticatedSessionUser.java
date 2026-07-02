package org.zipp.ai.trigger.http.service;

import java.io.Serializable;

/** Principal stored in the HTTP session; sessionVersion lets password resets stale old sessions. */
public final class AuthenticatedSessionUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String userId;
    private final int sessionVersion;

    public AuthenticatedSessionUser(String userId, int sessionVersion) {
        this.userId = userId;
        this.sessionVersion = sessionVersion;
    }

    public String getUserId() {
        return userId;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    @Override
    public String toString() {
        return userId;
    }
}
