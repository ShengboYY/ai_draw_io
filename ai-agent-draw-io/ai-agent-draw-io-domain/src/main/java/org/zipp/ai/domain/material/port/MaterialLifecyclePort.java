package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable owner-fenced boundary for aggregate lifecycle transitions and expiry scans. */
public interface MaterialLifecyclePort {
    Optional<Material> findOwned(CatalogOwner owner, String materialId);
    Optional<MaterialLifecycleResult> findApplied(CatalogOwner owner, String materialId,
                                                  MaterialLifecycleAction action,
                                                  String requestFingerprint);
    boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey);
    MaterialLifecycleResult apply(MaterialLifecycleMutation mutation);
    boolean originConversationAvailable(CatalogOwner owner, String conversationId);
    MaterialDeletionImpact findDeletionImpact(CatalogOwner owner, String materialId);
    List<MaterialExpiryCandidate> findExpiredTemporary(Instant now, int limit);
    List<MaterialExpiryCandidate> findExpiredTrash(Instant now, int limit);
    List<MaterialLifecycleCandidate> findOwnerDeletionCandidates(String ownerKey, int limit);
}
