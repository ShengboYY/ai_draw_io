package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.valobj.*;

/** Domain policy for durable retrieval scopes; associations never transfer ownership. */
public final class MaterialScopePolicy {
    public void validateAddition(MaterialScopeCommand command, MaterialCatalogDetails material,
                                 boolean targetOwned) {
        command.owner().requireRegisteredUser();
        if (material.material().lifecycleState() != MaterialLifecycleState.ACTIVE) {
            throw new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_ACTIVE);
        }
        if (material.material().retentionClass() != RetentionClass.RETAINED) {
            // Temporary-to-durable conversion has its own generation-fenced promote transaction.
            throw new CatalogOperationException(CatalogErrorCode.MATERIAL_PROMOTION_REQUIRED);
        }
        if (command.scopeType() == MaterialScopeType.CONVERSATION || !targetOwned) {
            throw new CatalogOperationException(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND);
        }
    }

    public void validateRemoval(MaterialCatalogDetails material, String linkId) {
        if (material.material().lifecycleState() != MaterialLifecycleState.ACTIVE) {
            throw new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_ACTIVE);
        }
        boolean exists = material.scopes().stream().anyMatch(scope -> scope.linkId().equals(linkId));
        if (!exists) throw new CatalogOperationException(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND);
        if (material.material().retentionClass() == RetentionClass.RETAINED
                && material.scopes().size() == 1) {
            // Retained content must be moved, saved elsewhere, or trashed instead of becoming hidden.
            throw new CatalogOperationException(CatalogErrorCode.LAST_RETAINED_SCOPE);
        }
    }
}
