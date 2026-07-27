package org.zipp.ai.domain.material.model.valobj;

public record MaterialScopeCommand(CatalogOwner owner, String materialId,
                                   MaterialScopeType scopeType, String scopeKey) {
    public MaterialScopeCommand {
        if (owner == null || materialId == null || materialId.isBlank()
                || scopeType == null || scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("material scope command is invalid");
        }
        materialId = materialId.trim();
        scopeKey = scopeKey.trim();
    }
}
