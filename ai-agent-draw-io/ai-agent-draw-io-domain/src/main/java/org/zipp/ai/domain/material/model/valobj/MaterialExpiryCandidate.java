package org.zipp.ai.domain.material.model.valobj;

import java.time.Instant;

public record MaterialExpiryCandidate(CatalogOwner owner, String materialId,
                                      long lifecycleGeneration, Instant expiresAt) {
    public MaterialExpiryCandidate {
        if (owner == null || materialId == null || materialId.isBlank() || expiresAt == null
                || lifecycleGeneration < 0) {
            throw new IllegalArgumentException("expiry candidate is invalid");
        }
    }
}
