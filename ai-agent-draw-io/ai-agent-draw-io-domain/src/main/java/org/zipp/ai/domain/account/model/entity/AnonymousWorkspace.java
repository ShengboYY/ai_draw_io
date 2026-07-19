package org.zipp.ai.domain.account.model.entity;

import org.zipp.ai.domain.account.model.valobj.AnonymousWorkspaceStatus;

import java.time.Instant;

/**
 * Aggregate root for anonymous ownership. The owner id is only a stable internal key; access is
 * granted solely while the associated credential remains ACTIVE.
 */
public final class AnonymousWorkspace {

    private final String ownerId;
    private final String credentialId;
    private final String credentialHash;
    private final Instant createdAt;
    private AnonymousWorkspaceStatus status;
    private String claimedByUserId;
    private Instant claimedAt;

    private AnonymousWorkspace(String ownerId,
                               String credentialId,
                               String credentialHash,
                               AnonymousWorkspaceStatus status,
                               Instant createdAt,
                               String claimedByUserId,
                               Instant claimedAt) {
        this.ownerId = require(ownerId, "ownerId");
        this.credentialId = require(credentialId, "credentialId");
        this.credentialHash = require(credentialHash, "credentialHash");
        this.status = status == null ? AnonymousWorkspaceStatus.ACTIVE : status;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.claimedByUserId = claimedByUserId;
        this.claimedAt = claimedAt;
    }

    public static AnonymousWorkspace issue(String ownerId,
                                           String credentialId,
                                           String credentialHash,
                                           Instant createdAt) {
        return new AnonymousWorkspace(ownerId, credentialId, credentialHash,
                AnonymousWorkspaceStatus.ACTIVE, createdAt, null, null);
    }

    public static AnonymousWorkspace restore(String ownerId,
                                             String credentialId,
                                             String credentialHash,
                                             AnonymousWorkspaceStatus status,
                                             Instant createdAt,
                                             String claimedByUserId,
                                             Instant claimedAt) {
        return new AnonymousWorkspace(ownerId, credentialId, credentialHash,
                status, createdAt, claimedByUserId, claimedAt);
    }

    public boolean acceptsCredential() {
        return status == AnonymousWorkspaceStatus.ACTIVE;
    }

    public void claimBy(String userId, Instant when) {
        if (!acceptsCredential()) {
            throw new IllegalStateException("Anonymous workspace is no longer claimable");
        }
        this.claimedByUserId = require(userId, "userId");
        this.claimedAt = when == null ? Instant.now() : when;
        this.status = AnonymousWorkspaceStatus.CLAIMED;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public String getCredentialHash() {
        return credentialHash;
    }

    public AnonymousWorkspaceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getClaimedByUserId() {
        return claimedByUserId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }
}
