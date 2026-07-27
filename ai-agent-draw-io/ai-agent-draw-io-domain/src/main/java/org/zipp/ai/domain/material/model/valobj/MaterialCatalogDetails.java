package org.zipp.ai.domain.material.model.valobj;

import java.util.List;
import java.util.Optional;

public record MaterialCatalogDetails(MaterialCatalogItem material,
                                     List<MaterialVersionSummary> versions,
                                     List<MaterialScopeReference> scopes) {
    public MaterialCatalogDetails {
        if (material == null) throw new IllegalArgumentException("material is required");
        versions = List.copyOf(versions);
        scopes = List.copyOf(scopes);
    }

    public Optional<MaterialScopeReference> findScope(MaterialScopeType scopeType, String scopeKey) {
        return scopes.stream().filter(scope -> scope.scopeType() == scopeType
                && scope.scopeKey().equals(scopeKey)).findFirst();
    }
}
