package org.zipp.ai.domain.account.model.valobj;

/** Result returned once when the server creates an anonymous workspace. */
public final class IssuedAnonymousWorkspace {

    private final String ownerId;
    private final String rawCredential;

    public IssuedAnonymousWorkspace(String ownerId, String rawCredential) {
        if (ownerId == null || ownerId.isBlank() || rawCredential == null || rawCredential.isBlank()) {
            throw new IllegalArgumentException("Issued workspace requires owner and credential");
        }
        this.ownerId = ownerId;
        this.rawCredential = rawCredential;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getRawCredential() {
        return rawCredential;
    }
}
