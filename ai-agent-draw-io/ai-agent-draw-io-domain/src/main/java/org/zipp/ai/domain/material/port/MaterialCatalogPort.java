package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.valobj.*;

import java.util.Optional;

/** Owner-fenced persistence boundary for catalog metadata and scope links. */
public interface MaterialCatalogPort {
    MaterialCatalogPage findMaterials(MaterialCatalogQuery query);
    Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId);
    boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey);
    boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope);
    boolean removeScope(CatalogOwner owner, String materialId, String linkId);
}
