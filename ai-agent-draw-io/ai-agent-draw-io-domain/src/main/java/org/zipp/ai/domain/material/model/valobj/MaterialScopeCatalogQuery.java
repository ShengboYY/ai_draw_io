package org.zipp.ai.domain.material.model.valobj;

/** Owner-fenced metadata query for one durable diagram or chartbook scope. */
public record MaterialScopeCatalogQuery(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey,
                                        MaterialLifecycleState lifecycleState, int limit, int offset) {
    public MaterialScopeCatalogQuery {
        if (owner == null || scopeType == null || scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("owner/scope required");
        }
        if (scopeType == MaterialScopeType.CONVERSATION) {
            throw new IllegalArgumentException("conversation scope cannot be catalogued");
        }
        scopeKey = scopeKey.trim();
        lifecycleState = lifecycleState == null ? MaterialLifecycleState.ACTIVE : lifecycleState;
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("catalog query bounds are invalid");
        }
    }
}
