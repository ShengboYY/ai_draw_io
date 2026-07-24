package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;

import java.util.Objects;

/** Application service for metadata-only material browsing and durable scope mutations. */
public final class MaterialCatalogService {
    private final MaterialCatalogPort catalog;
    private final CatalogIdFactory ids;
    private final MaterialScopePolicy scopePolicy;

    public MaterialCatalogService(MaterialCatalogPort catalog, CatalogIdFactory ids,
                                  MaterialScopePolicy scopePolicy) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.scopePolicy = Objects.requireNonNull(scopePolicy, "scopePolicy");
    }

    public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
        query.owner().requireRegisteredUser();
        return catalog.findMaterials(query);
    }

    public MaterialCatalogPage findMaterialsForScope(MaterialScopeCatalogQuery query) {
        // Anonymous uploads are visible only through their exact owner-fenced conversation scope.
        if (query.scopeType() != MaterialScopeType.CONVERSATION) {
            query.owner().requireRegisteredUser();
        }
        if (!catalog.scopeTargetOwned(query.owner(), query.scopeType(), query.scopeKey())) {
            throw new CatalogOperationException(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND);
        }
        return catalog.findMaterialsForScope(query);
    }

    public MaterialCatalogDetails findMaterial(CatalogOwner owner, String materialId) {
        owner.requireRegisteredUser();
        return catalog.findMaterial(owner, required(materialId, "materialId"))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_FOUND));
    }

    public MaterialCatalogDetails addScope(MaterialScopeCommand command) {
        MaterialScopeCommand normalized = normalize(command);
        MaterialCatalogDetails material = findMaterial(normalized.owner(), normalized.materialId());
        boolean targetOwned = catalog.scopeTargetOwned(
                normalized.owner(), normalized.scopeType(), normalized.scopeKey());
        scopePolicy.validateAddition(normalized, material, targetOwned);
        MaterialScopeReference scope = new MaterialScopeReference(ids.nextMaterialScopeLinkId(),
                normalized.scopeType(), normalized.scopeKey());
        if (!catalog.addScope(normalized.owner(), normalized.materialId(), scope)) {
            // A concurrent idempotent request may have won the unique scope insert.
            MaterialCatalogDetails concurrent = findMaterial(
                    normalized.owner(), normalized.materialId());
            if (concurrent.findScope(normalized.scopeType(), normalized.scopeKey()).isPresent()) {
                return concurrent;
            }
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        return findMaterial(normalized.owner(), normalized.materialId());
    }

    public MaterialCatalogDetails removeScope(CatalogOwner owner, String materialId, String linkId) {
        owner.requireRegisteredUser();
        MaterialCatalogDetails material = findMaterial(owner, materialId);
        String scopeLinkId = required(linkId, "linkId");
        scopePolicy.validateRemoval(material, scopeLinkId);
        if (!catalog.removeScope(owner, materialId, scopeLinkId)) {
            MaterialCatalogDetails concurrent = findMaterial(owner, materialId);
            if (concurrent.scopes().stream().noneMatch(scope -> scope.linkId().equals(scopeLinkId))) {
                return concurrent;
            }
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        return findMaterial(owner, materialId);
    }

    public MaterialCatalogDetails removeLastScopeAndTrash(
            CatalogOwner owner, String materialId, String linkId) {
        owner.requireRegisteredUser();
        MaterialCatalogDetails material = findMaterial(owner, materialId);
        String scopeLinkId = required(linkId, "linkId");
        if (lastScopeRemovalReached(material, scopeLinkId)) return material;
        if (material.material().retentionClass() != RetentionClass.RETAINED
                || material.scopes().size() != 1
                || !material.scopes().get(0).linkId().equals(scopeLinkId)) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        if (!catalog.removeLastScopeAndTrash(owner, materialId, scopeLinkId)) {
            MaterialCatalogDetails concurrent = findMaterial(owner, materialId);
            if (lastScopeRemovalReached(concurrent, scopeLinkId)) return concurrent;
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        return findMaterial(owner, materialId);
    }

    private boolean lastScopeRemovalReached(MaterialCatalogDetails material, String scopeLinkId) {
        return material.material().lifecycleState() == MaterialLifecycleState.TRASHED
                && material.scopes().stream().noneMatch(scope -> scope.linkId().equals(scopeLinkId));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private MaterialScopeCommand normalize(MaterialScopeCommand command) {
        // Personal-library identity is tenant-local; one canonical key prevents duplicate aliases.
        if (command.scopeType() == MaterialScopeType.LIBRARY) {
            return new MaterialScopeCommand(command.owner(), command.materialId(),
                    command.scopeType(), MaterialScopeType.PERSONAL_LIBRARY_KEY);
        }
        return command;
    }
}
