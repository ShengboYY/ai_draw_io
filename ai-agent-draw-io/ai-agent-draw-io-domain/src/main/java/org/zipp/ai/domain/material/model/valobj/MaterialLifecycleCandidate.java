package org.zipp.ai.domain.material.model.valobj;

/** Owner and generation fence used by non-expiry lifecycle maintenance. */
public record MaterialLifecycleCandidate(CatalogOwner owner, String materialId, long lifecycleGeneration) {
    public MaterialLifecycleCandidate {
        if (owner == null || materialId == null || materialId.isBlank() || lifecycleGeneration < 0) {
            throw new IllegalArgumentException("lifecycle candidate is invalid");
        }
    }
}
