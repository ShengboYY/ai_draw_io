package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.infrastructure.dao.material.IMaterialCatalogMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MySQL metadata adapter; every read and mutation is fenced by the authoritative owner tuple. */
@Repository
public class MySqlMaterialCatalogAdapter implements MaterialCatalogPort {
    private final IMaterialCatalogMapper mapper;

    public MySqlMaterialCatalogAdapter(IMaterialCatalogMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
        CatalogOwner owner = query.owner();
        List<MaterialCatalogItem> items = mapper.selectLibraryMaterials(owner.ownerType().name(),
                owner.ownerKey(), query.lifecycleState().name(), query.query(), query.limit(), query.offset())
                .stream().map(this::item).toList();
        long total = mapper.countLibraryMaterials(owner.ownerType().name(), owner.ownerKey(),
                query.lifecycleState().name(), query.query());
        return new MaterialCatalogPage(items, total, query.limit(), query.offset());
    }

    @Override
    public MaterialCatalogPage findMaterialsForScope(MaterialScopeCatalogQuery query) {
        CatalogOwner owner = query.owner();
        List<MaterialCatalogItem> items = mapper.selectScopedMaterials(owner.ownerType().name(), owner.ownerKey(),
                query.scopeType().name(), query.scopeKey(), query.lifecycleState().name(), query.limit(), query.offset())
                .stream().map(this::item).toList();
        long total = mapper.countScopedMaterials(owner.ownerType().name(), owner.ownerKey(),
                query.scopeType().name(), query.scopeKey(), query.lifecycleState().name());
        return new MaterialCatalogPage(items, total, query.limit(), query.offset());
    }

    @Override
    public Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId) {
        MaterialCatalogItemPO material = mapper.selectOwnedMaterial(
                owner.ownerType().name(), owner.ownerKey(), materialId);
        if (material == null) return Optional.empty();
        List<MaterialVersionSummary> versions = mapper.selectOwnedVersions(owner.ownerKey(), materialId)
                .stream().map(this::version).toList();
        List<MaterialScopeReference> scopes = mapper.selectOwnedScopes(
                        owner.ownerType().name(), owner.ownerKey(), materialId)
                .stream().map(this::scope).toList();
        return Optional.of(new MaterialCatalogDetails(item(material), versions, scopes));
    }

    @Override
    public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
        return switch (scopeType) {
            case LIBRARY -> MaterialScopeType.isPersonalLibraryKey(scopeKey, owner.ownerKey());
            case DIAGRAM -> mapper.countOwnedDiagram(owner.ownerKey(), scopeKey) == 1;
            case CHARTBOOK -> mapper.countOwnedActiveChartbook(owner.ownerKey(), scopeKey) == 1;
            // Conversation listings remain owner-fenced by the Material row and exact scope link.
            case CONVERSATION -> true;
        };
    }

    @Override
    @Transactional
    public boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope) {
        if (mapper.lockOwnedActiveMaterial(owner.ownerType().name(), owner.ownerKey(), materialId) == null) {
            return false;
        }
        mapper.insertOwnedScope(scope.linkId(), owner.ownerType().name(), owner.ownerKey(), materialId,
                scope.scopeType().name(), scope.scopeKey());
        return mapper.countScope(materialId, scope.scopeType().name(), scope.scopeKey()) == 1;
    }

    @Override
    @Transactional
    public boolean removeScope(CatalogOwner owner, String materialId, String linkId) {
        if (mapper.lockOwnedActiveMaterial(owner.ownerType().name(), owner.ownerKey(), materialId) == null
                || mapper.countOwnedScopes(materialId) <= 1) {
            return false;
        }
        if (mapper.deleteOwnedScope(materialId, linkId) != 1) return false;
        // A promoted conversation file becomes TTL-bound again after its last durable scope leaves.
        mapper.restoreTemporaryWhenConversationOnly(materialId);
        return true;
    }

    @Override
    @Transactional
    public boolean removeLastScopeAndTrash(CatalogOwner owner, String materialId, String linkId) {
        if (mapper.lockOwnedActiveMaterial(owner.ownerType().name(), owner.ownerKey(), materialId) == null
                || mapper.countOwnedScopes(materialId) != 1
                || mapper.deleteOwnedScope(materialId, linkId) != 1) {
            return false;
        }
        if (mapper.trashOwnedMaterial(owner.ownerType().name(), owner.ownerKey(), materialId) != 1) {
            // Throw so Spring rolls the deleted scope back with the failed lifecycle transition.
            throw new IllegalStateException("failed to trash material after removing its final scope");
        }
        return true;
    }

    private MaterialCatalogItem item(MaterialCatalogItemPO po) {
        return new MaterialCatalogItem(po.getMaterialId(), MaterialKind.valueOf(po.getKind()),
                po.getDisplayName(), RetentionClass.valueOf(po.getRetentionClass()),
                MaterialLifecycleState.valueOf(po.getLifecycleState()), po.getLatestVersionId(),
                po.getLatestVersionNo(), CatalogProcessingStatus.from(po.getIngestState(),
                po.getProcessingState(), po.getProcessingStage()), po.getProgress(),
                CatalogSearchStatus.valueOf(po.getSearchStatus()), po.getPageCount(), po.getUpdatedAt());
    }

    private MaterialVersionSummary version(MaterialCatalogVersionPO po) {
        return new MaterialVersionSummary(po.getVersionId(), po.getVersionNo(), po.getDetectedMime(),
                po.getByteSize(), po.getPageCount(), CatalogProcessingStatus.from(po.getIngestState(),
                po.getProcessingState(), po.getProcessingStage()), po.getProgress(), po.getCreatedAt());
    }

    private MaterialScopeReference scope(MaterialScopeLinkPO po) {
        return new MaterialScopeReference(po.getId(), MaterialScopeType.valueOf(po.getScopeType()),
                po.getScopeKey());
    }
}
