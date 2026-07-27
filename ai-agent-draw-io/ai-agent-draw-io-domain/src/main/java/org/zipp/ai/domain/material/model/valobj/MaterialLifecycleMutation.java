package org.zipp.ai.domain.material.model.valobj;

import org.zipp.ai.domain.material.model.aggregate.Material;

import java.time.Instant;

/** Optimistically fenced lifecycle transition produced by the Material aggregate. */
public record MaterialLifecycleMutation(CatalogOwner owner, MaterialLifecycleAction action,
                                        String requestFingerprint, long expectedGeneration,
                                        Material material, MaterialScopeReference scopeLink,
                                        String deletionTaskId, Instant requestedAt,
                                        String expectedDeletionImpactFingerprint) {
    public MaterialLifecycleMutation(CatalogOwner owner, MaterialLifecycleAction action,
                                     String requestFingerprint, long expectedGeneration,
                                     Material material, MaterialScopeReference scopeLink,
                                     String deletionTaskId, Instant requestedAt) {
        this(owner, action, requestFingerprint, expectedGeneration, material, scopeLink,
                deletionTaskId, requestedAt, null);
    }

    public MaterialLifecycleMutation {
        if (owner == null || action == null || material == null || requestedAt == null) {
            throw new IllegalArgumentException("lifecycle mutation facts are required");
        }
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestFingerprint must be lowercase SHA-256");
        }
        if (expectedGeneration < 0 || material.lifecycleGeneration() < expectedGeneration) {
            throw new IllegalArgumentException("lifecycle generation is invalid");
        }
        if (material.lifecycleState() == MaterialLifecycleState.DELETE_PENDING
                && (deletionTaskId == null || deletionTaskId.isBlank())) {
            throw new IllegalArgumentException("delete-pending mutation requires a deletion task");
        }
        if (expectedDeletionImpactFingerprint != null
                && !expectedDeletionImpactFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected deletion impact fingerprint must be SHA-256");
        }
    }
}
