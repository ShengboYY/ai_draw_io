package org.zipp.ai.domain.material.model.valobj;

public record MaterialCatalogQuery(CatalogOwner owner, String query,
                                   MaterialLifecycleState lifecycleState, int limit, int offset) {
    public MaterialCatalogQuery {
        if (owner == null) throw new IllegalArgumentException("owner is required");
        query = query == null || query.isBlank() ? null : query.trim();
        lifecycleState = lifecycleState == null ? MaterialLifecycleState.ACTIVE : lifecycleState;
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("catalog query bounds are invalid");
        }
    }
}
