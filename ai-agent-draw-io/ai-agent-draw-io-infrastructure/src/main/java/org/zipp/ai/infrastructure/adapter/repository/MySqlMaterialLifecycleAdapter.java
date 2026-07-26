package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.domain.material.port.MaterialLifecyclePort;
import org.zipp.ai.infrastructure.dao.material.IMaterialLifecycleMapper;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialDeletionImpactPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialScopeLinkPO;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** MySQL lifecycle repository; every state change is generation-fenced under the material row lock. */
@Repository
public class MySqlMaterialLifecycleAdapter implements MaterialLifecyclePort {
    private final IMaterialLifecycleMapper mapper;
    private final MySqlConversationScopeKeyResolver conversationScopes;

    public MySqlMaterialLifecycleAdapter(IMaterialLifecycleMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public MySqlMaterialLifecycleAdapter(IMaterialLifecycleMapper mapper,
                                         MySqlConversationScopeKeyResolver conversationScopes) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.conversationScopes = conversationScopes;
    }

    @Override
    public Optional<Material> findOwned(CatalogOwner owner, String materialId) {
        MaterialPO po = mapper.selectOwnedLifecycle(owner.ownerType().name(), owner.ownerKey(), materialId);
        return po == null ? Optional.empty() : Optional.of(toDomain(po,
                mapper.selectLifecycleScopes(materialId)));
    }

    @Override
    public Optional<MaterialLifecycleResult> findApplied(CatalogOwner owner, String materialId,
                                                         MaterialLifecycleAction action,
                                                         String requestFingerprint) {
        MaterialPO po = mapper.selectLifecycleRequest(owner.ownerKey(), materialId,
                action.name(), requestFingerprint);
        return po == null ? Optional.empty() : Optional.of(result(po));
    }

    @Override
    public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
        return switch (scopeType) {
            case LIBRARY -> MaterialScopeType.isPersonalLibraryKey(scopeKey, owner.ownerKey());
            case DIAGRAM -> mapper.countOwnedLifecycleDiagram(owner.ownerKey(), scopeKey) == 1;
            case CHARTBOOK -> mapper.countOwnedLifecycleChartbook(owner.ownerKey(), scopeKey) == 1;
            default -> false;
        };
    }

    @Override
    @Transactional
    public MaterialLifecycleResult apply(MaterialLifecycleMutation mutation) {
        MaterialPO prior = mapper.selectLifecycleRequest(mutation.owner().ownerKey(),
                mutation.material().id(), mutation.action().name(), mutation.requestFingerprint());
        if (prior != null) return result(prior);

        MaterialPO locked = mapper.lockOwnedLifecycle(mutation.owner().ownerType().name(),
                mutation.owner().ownerKey(), mutation.material().id());
        if (locked == null) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        if (locked.getLifecycleGeneration() != mutation.expectedGeneration()) {
            // A concurrent retry may have committed while this transaction waited on the Material row lock.
            MaterialPO concurrent = mapper.selectLifecycleRequestForUpdate(mutation.owner().ownerKey(),
                    mutation.material().id(), mutation.action().name(), mutation.requestFingerprint());
            if (concurrent != null) return result(concurrent);
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        if (mutation.action() == MaterialLifecycleAction.RESTORE
                && mapper.countPendingVectorCleanup(mutation.material().id()) > 0) {
            // Restore must wait until every claimed external vector deletion is durably complete.
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        // Meaningful activity does not invalidate processing generations, so TTL must recheck expiry under this lock.
        if (mutation.action() == MaterialLifecycleAction.TTL_EXPIRE
                && (locked.getExpiresAt() == null || locked.getExpiresAt().isAfter(mutation.requestedAt()))) {
            return result(locked);
        }
        if (mutation.action() == MaterialLifecycleAction.MEANINGFUL_ACTIVITY
                && !mutation.material().lastMeaningfulActivityAt()
                .isAfter(locked.getLastMeaningfulActivityAt())) {
            return result(locked);
        }
        if (mutation.expectedDeletionImpactFingerprint() != null) {
            MaterialDeletionImpactPO current = mapper.selectDeletionImpact(
                    mutation.owner().ownerType().name(), mutation.owner().ownerKey(), mutation.material().id());
            MaterialDeletionImpact impact = impact(current);
            if (!impact.fingerprint().equals(mutation.expectedDeletionImpactFingerprint())) {
                throw new CatalogOperationException(CatalogErrorCode.DELETION_CONFIRMATION_INVALID);
            }
        }
        MaterialPO target = toPo(mutation.material());
        String scopeKey = mutation.scopeLink() == null ? null
                : canonicalScopeKey(mutation.owner(), mutation.scopeLink().scopeType(),
                mutation.scopeLink().scopeKey());
        if (mutation.scopeLink() != null && mapper.insertLifecycleScope(
                mutation.scopeLink().linkId(), target.getId(), target.getOwnerType(), target.getOwnerKey(),
                mutation.scopeLink().scopeType().name(), scopeKey) != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        if (mapper.updateLifecycle(target, mutation.expectedGeneration()) != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        if (mutation.material().lifecycleState() == MaterialLifecycleState.DELETE_PENDING) {
            // Lifecycle generation fences running workers; queued jobs are cancelled eagerly.
            mapper.cancelQueuedMaterialJobs(target.getId());
            if (mapper.insertDeletionTask(mutation.deletionTaskId(), target.getId(),
                    target.getLifecycleGeneration(), mutation.requestedAt()) != 1) {
                throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
            }
        }
        if (mutation.action() != MaterialLifecycleAction.MEANINGFUL_ACTIVITY
                && mapper.insertLifecycleRequest(mutation.owner().ownerKey(), mutation.action().name(),
                mutation.requestFingerprint(), target) != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        return MaterialLifecycleResult.from(mutation.material());
    }

    @Override
    public boolean originConversationAvailable(CatalogOwner owner, String conversationId) {
        if (conversationScopes == null) {
            return mapper.countOriginConversation(owner.ownerKey(), conversationId) == 1;
        }
        return mapper.countOriginConversationByKeys(owner.ownerKey(), readableConversationKeys(owner, conversationId)) > 0;
    }

    private List<String> readableConversationKeys(CatalogOwner owner, String conversationId) {
        return conversationScopes.readableScopeKeys(
                new AuthenticatedActor(owner.ownerKey(), owner.ownerKey()), conversationId, null).allKeys();
    }

    private String canonicalScopeKey(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
        if (scopeType != MaterialScopeType.CONVERSATION || conversationScopes == null) {
            return scopeKey;
        }
        return conversationScopes.newWriteScopeKey(
                new AuthenticatedActor(owner.ownerKey(), owner.ownerKey()), scopeKey, null);
    }

    @Override
    public MaterialDeletionImpact findDeletionImpact(CatalogOwner owner, String materialId) {
        MaterialDeletionImpactPO po = mapper.selectDeletionImpact(
                owner.ownerType().name(), owner.ownerKey(), materialId);
        if (po == null) throw new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_FOUND);
        return impact(po);
    }

    @Override
    public List<MaterialExpiryCandidate> findExpiredTemporary(Instant now, int limit) {
        return mapper.selectExpiredTemporary(now, positive(limit)).stream().map(this::candidate).toList();
    }

    @Override
    public List<MaterialExpiryCandidate> findExpiredTrash(Instant now, int limit) {
        return mapper.selectExpiredTrash(now, positive(limit)).stream().map(this::candidate).toList();
    }

    @Override
    public List<MaterialLifecycleCandidate> findOwnerDeletionCandidates(String ownerKey, int limit) {
        if (ownerKey == null || ownerKey.isBlank()) throw new IllegalArgumentException("ownerKey is required");
        return mapper.selectOwnerDeletionCandidates(ownerKey.trim(), positive(limit)).stream()
                .map(po -> new MaterialLifecycleCandidate(new CatalogOwner(OwnerType.USER, po.getOwnerKey()),
                        po.getId(), po.getLifecycleGeneration())).toList();
    }

    private Material toDomain(MaterialPO po, List<MaterialScopeLinkPO> scopeRows) {
        OwnerType ownerType = OwnerType.valueOf(po.getOwnerType());
        MaterialKind kind = MaterialKind.valueOf(po.getKind());
        RetentionClass retention = RetentionClass.valueOf(po.getRetentionClass());
        Set<MaterialScopeLink> scopes = new LinkedHashSet<>();
        for (MaterialScopeLinkPO row : scopeRows) {
            scopes.add(new MaterialScopeLink(MaterialScopeType.valueOf(row.getScopeType()),
                    row.getScopeKey(), row.getCreatedBy()));
        }
        MaterialLifecycleState state = MaterialLifecycleState.valueOf(po.getLifecycleState());
        if (state == MaterialLifecycleState.ACTIVE && retention == RetentionClass.TEMPORARY) {
            return Material.rehydrateTemporaryActive(po.getId(), ownerType, po.getOwnerKey(), kind,
                    po.getDisplayName(), po.getOriginConversationId(), po.getLifecycleGeneration(),
                    po.getLastMeaningfulActivityAt(), po.getExpiresAt());
        }
        if (state == MaterialLifecycleState.ACTIVE) {
            return Material.rehydrateRetainedActive(po.getId(), ownerType, po.getOwnerKey(), kind,
                    po.getDisplayName(), po.getLifecycleGeneration(), po.getLastMeaningfulActivityAt(), scopes);
        }
        if (state == MaterialLifecycleState.TRASHED) {
            return Material.rehydrateTrashed(po.getId(), ownerType, po.getOwnerKey(), kind,
                    po.getDisplayName(), retention, po.getOriginConversationId(),
                    po.getLifecycleGeneration(), po.getLastMeaningfulActivityAt(), po.getExpiresAt(),
                    po.getTrashExpiresAt(), scopes);
        }
        throw new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_ACTIVE);
    }

    private MaterialPO toPo(Material material) {
        MaterialPO po = new MaterialPO();
        po.setId(material.id());
        po.setOwnerType(material.ownerType().name());
        po.setOwnerKey(material.ownerKey());
        po.setKind(material.kind().name());
        po.setDisplayName(material.displayName());
        po.setRetentionClass(material.retentionClass().name());
        po.setOriginConversationId(material.originConversationId());
        po.setLifecycleState(material.lifecycleState().name());
        po.setLifecycleGeneration(material.lifecycleGeneration());
        po.setLastMeaningfulActivityAt(material.lastMeaningfulActivityAt());
        po.setExpiresAt(material.expiresAt());
        po.setTrashExpiresAt(material.trashExpiresAt());
        po.setDeletedAt(material.deletedAt());
        return po;
    }

    private MaterialLifecycleResult result(MaterialPO po) {
        return new MaterialLifecycleResult(po.getId(), RetentionClass.valueOf(po.getRetentionClass()),
                MaterialLifecycleState.valueOf(po.getLifecycleState()), po.getLifecycleGeneration(),
                po.getExpiresAt(), po.getTrashExpiresAt());
    }

    private MaterialExpiryCandidate candidate(MaterialPO po) {
        Instant expiry = MaterialLifecycleState.TRASHED.name().equals(po.getLifecycleState())
                ? po.getTrashExpiresAt() : po.getExpiresAt();
        return new MaterialExpiryCandidate(new CatalogOwner(OwnerType.valueOf(po.getOwnerType()),
                po.getOwnerKey()), po.getId(), po.getLifecycleGeneration(), expiry);
    }

    private MaterialDeletionImpact impact(MaterialDeletionImpactPO po) {
        return new MaterialDeletionImpact(po.getMaterialId(), po.getLifecycleGeneration(),
                mapper.selectDeletionImpactVersionIds(po.getMaterialId()),
                mapper.selectDeletionImpactPinnedSourceIds(po.getMaterialId()),
                mapper.selectDeletionImpactDiagramIds(po.getMaterialId()),
                mapper.selectDeletionImpactChartbookIds(po.getMaterialId()),
                mapper.selectDeletionImpactCitationIds(po.getMaterialId()));
    }

    private int positive(int value) {
        if (value < 1 || value > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return value;
    }
}
