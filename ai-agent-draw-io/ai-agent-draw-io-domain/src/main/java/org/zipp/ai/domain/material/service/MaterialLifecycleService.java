package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialLifecyclePort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Coordinates user-visible lifecycle changes while the Material aggregate owns state rules. */
public final class MaterialLifecycleService implements MaterialDeletionModule {
    private final MaterialLifecyclePort lifecycle;
    private final CatalogIdFactory ids;
    private final Clock clock;

    public MaterialLifecycleService(MaterialLifecyclePort lifecycle, CatalogIdFactory ids, Clock clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MaterialLifecycleResult promote(CatalogOwner owner, String materialId,
                                           MaterialScopeType scopeType, String scopeKey,
                                           String idempotencyKey) {
        owner.requireRegisteredUser();
        // Promotion owns the durable scope insert so retention and Chartbook visibility change atomically.
        if (scopeType != MaterialScopeType.LIBRARY && scopeType != MaterialScopeType.DIAGRAM
                && scopeType != MaterialScopeType.CHARTBOOK) {
            throw new IllegalArgumentException(
                    "temporary material can be retained only in a library, diagram, or chartbook");
        }
        String normalizedScope = scopeType == MaterialScopeType.LIBRARY
                ? MaterialScopeType.PERSONAL_LIBRARY_KEY : required(scopeKey, "scopeKey");
        MaterialLifecycleResult previous = previous(owner, materialId, MaterialLifecycleAction.PROMOTE,
                idempotencyKey);
        if (previous != null) return previous;
        if (!lifecycle.scopeTargetOwned(owner, scopeType, normalizedScope)) {
            throw new CatalogOperationException(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND);
        }
        Material material = owned(owner, materialId);
        if (material.retentionClass() != RetentionClass.TEMPORARY) {
            throw new CatalogOperationException(CatalogErrorCode.TEMPORARY_MATERIAL_REQUIRED);
        }
        long expected = material.lifecycleGeneration();
        MaterialScopeReference reference = new MaterialScopeReference(ids.nextMaterialScopeLinkId(),
                scopeType, normalizedScope);
        material.retain(new MaterialScopeLink(scopeType, normalizedScope, owner.ownerKey()), clock.instant());
        return lifecycle.apply(mutation(owner, MaterialLifecycleAction.PROMOTE, idempotencyKey,
                expected, material, reference));
    }

    public MaterialLifecycleResult remove(CatalogOwner owner, String materialId, String idempotencyKey) {
        MaterialLifecycleResult previous = previous(owner, materialId, MaterialLifecycleAction.REMOVE,
                idempotencyKey);
        if (previous != null) return previous;
        Material material = owned(owner, materialId);
        long expected = material.lifecycleGeneration();
        material.remove(clock.instant());
        return lifecycle.apply(mutation(owner, MaterialLifecycleAction.REMOVE, idempotencyKey,
                expected, material, null));
    }

    public MaterialLifecycleResult restore(CatalogOwner owner, String materialId, String idempotencyKey) {
        owner.requireRegisteredUser();
        MaterialLifecycleResult previous = previous(owner, materialId, MaterialLifecycleAction.RESTORE,
                idempotencyKey);
        if (previous != null) return previous;
        Material material = owned(owner, materialId);
        if (material.retentionClass() == RetentionClass.TEMPORARY
                && !lifecycle.originConversationAvailable(owner, material.originConversationId())) {
            throw new CatalogOperationException(CatalogErrorCode.RESTORE_TARGET_UNAVAILABLE);
        }
        if (material.retentionClass() == RetentionClass.RETAINED && material.scopeLinks().isEmpty()) {
            // A last-scope removal is recoverable trash, but restoring it requires an explicit future target.
            throw new CatalogOperationException(CatalogErrorCode.RESTORE_TARGET_UNAVAILABLE);
        }
        long expected = material.lifecycleGeneration();
        material.restore(clock.instant());
        return lifecycle.apply(mutation(owner, MaterialLifecycleAction.RESTORE, idempotencyKey,
                expected, material, null));
    }

    public MaterialLifecycleResult recordMeaningfulActivity(CatalogOwner owner, String materialId) {
        Material material = owned(owner, materialId);
        long expected = material.lifecycleGeneration();
        try {
            material.recordMeaningfulActivity(clock.instant());
        } catch (IllegalStateException e) {
            throw new CatalogOperationException(CatalogErrorCode.MATERIAL_EXPIRED);
        }
        String key = "activity:" + material.lifecycleGeneration() + ":" + clock.instant();
        return lifecycle.apply(mutation(owner, MaterialLifecycleAction.MEANINGFUL_ACTIVITY, key,
                expected, material, null));
    }

    public MaterialLifecycleResult requestPermanentDeletion(CatalogOwner owner, String materialId,
                                                             String idempotencyKey) {
        return requestPermanentDeletion(owner, materialId, idempotencyKey, null);
    }

    public MaterialLifecycleResult requestPermanentDeletion(CatalogOwner owner, String materialId,
                                                             String idempotencyKey,
                                                             String expectedImpactFingerprint) {
        owner.requireRegisteredUser();
        MaterialLifecycleResult previous = previous(owner, materialId,
                MaterialLifecycleAction.PERMANENT_DELETE, idempotencyKey);
        if (previous != null) return previous;
        Material material = owned(owner, materialId);
        long expected = material.lifecycleGeneration();
        material.requestPermanentDeletion();
        MaterialLifecycleMutation mutation = mutation(owner, MaterialLifecycleAction.PERMANENT_DELETE,
                idempotencyKey, expected, material, null);
        return lifecycle.apply(new MaterialLifecycleMutation(mutation.owner(), mutation.action(),
                mutation.requestFingerprint(), mutation.expectedGeneration(), mutation.material(),
                mutation.scopeLink(), mutation.deletionTaskId(), mutation.requestedAt(),
                expectedImpactFingerprint));
    }

    /** Moves due temporary materials into user trash or anonymous permanent deletion. */
    public int expireTemporary(int limit) {
        Instant now = clock.instant();
        int changed = 0;
        for (MaterialExpiryCandidate candidate : lifecycle.findExpiredTemporary(now, limit)) {
            Material material = currentCandidate(candidate);
            if (material == null || material.expiresAt() == null || material.expiresAt().isAfter(now)) continue;
            long expected = material.lifecycleGeneration();
            material.remove(now);
            MaterialLifecycleResult result = lifecycle.apply(systemMutation(candidate.owner(),
                    MaterialLifecycleAction.TTL_EXPIRE, expected, material,
                    "ttl:" + candidate.lifecycleGeneration()));
            if (result.lifecycleGeneration() != expected) changed++;
        }
        return changed;
    }

    /** Starts irreversible deletion after the registered user's 30-day trash period. */
    public int expireTrash(int limit) {
        Instant now = clock.instant();
        int changed = 0;
        for (MaterialExpiryCandidate candidate : lifecycle.findExpiredTrash(now, limit)) {
            Material material = currentCandidate(candidate);
            if (material == null || material.trashExpiresAt() == null
                    || material.trashExpiresAt().isAfter(now)) continue;
            long expected = material.lifecycleGeneration();
            material.requestPermanentDeletion();
            MaterialLifecycleResult result = lifecycle.apply(systemMutation(candidate.owner(),
                    MaterialLifecycleAction.TRASH_EXPIRE, expected, material,
                    "trash:" + candidate.lifecycleGeneration()));
            if (result.lifecycleGeneration() != expected) changed++;
        }
        return changed;
    }

    @Override
    public void requestAccountDeletion(String ownerKey, Instant deletedAt) {
        CatalogOwner owner = new CatalogOwner(org.zipp.ai.domain.account.model.valobj.OwnerType.USER,
                required(ownerKey, "ownerKey"));
        // Account deletion is synchronous at the bounded-context seam; each aggregate remains independently fenced.
        while (true) {
            var candidates = lifecycle.findOwnerDeletionCandidates(owner.ownerKey(), 100);
            if (candidates.isEmpty()) return;
            for (MaterialLifecycleCandidate candidate : candidates) {
                Material material = currentCandidate(candidate);
                if (material == null) continue;
                long expected = material.lifecycleGeneration();
                material.requestPermanentDeletion();
                lifecycle.apply(systemMutation(owner, MaterialLifecycleAction.ACCOUNT_DELETE,
                        expected, material, "account:" + candidate.lifecycleGeneration() + ":" + deletedAt));
            }
        }
    }

    private Material currentCandidate(MaterialLifecycleCandidate candidate) {
        return lifecycle.findOwned(candidate.owner(), candidate.materialId())
                .filter(material -> material.lifecycleGeneration() == candidate.lifecycleGeneration())
                .orElse(null);
    }

    private Material currentCandidate(MaterialExpiryCandidate candidate) {
        return lifecycle.findOwned(candidate.owner(), candidate.materialId())
                .filter(material -> material.lifecycleGeneration() == candidate.lifecycleGeneration())
                .orElse(null);
    }

    private MaterialLifecycleMutation systemMutation(CatalogOwner owner, MaterialLifecycleAction action,
                                                      long expected, Material material, String stableKey) {
        return new MaterialLifecycleMutation(owner, action, sha256(action.name() + ":" + stableKey),
                expected, material, null,
                material.lifecycleState() == MaterialLifecycleState.DELETE_PENDING
                        ? ids.nextDeletionTaskId() : null, clock.instant());
    }

    private MaterialLifecycleMutation mutation(CatalogOwner owner, MaterialLifecycleAction action,
                                                String idempotencyKey, long expected,
                                                Material material, MaterialScopeReference scope) {
        return new MaterialLifecycleMutation(owner, action,
                sha256(action.name() + ":" + requiredKey(idempotencyKey)), expected,
                material, scope,
                material.lifecycleState() == MaterialLifecycleState.DELETE_PENDING
                        ? ids.nextDeletionTaskId() : null,
                clock.instant());
    }

    private MaterialLifecycleResult previous(CatalogOwner owner, String materialId,
                                             MaterialLifecycleAction action, String idempotencyKey) {
        String id = required(materialId, "materialId");
        String fingerprint = sha256(action.name() + ":" + requiredKey(idempotencyKey));
        return lifecycle.findApplied(owner, id, action, fingerprint).orElse(null);
    }

    private Material owned(CatalogOwner owner, String materialId) {
        return lifecycle.findOwned(owner, required(materialId, "materialId"))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_FOUND));
    }

    private String requiredKey(String value) {
        String key = required(value, "Idempotency-Key");
        if (key.length() > 128) throw new IllegalArgumentException("Idempotency-Key is too long");
        return key;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
