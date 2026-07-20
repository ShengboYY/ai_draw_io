package org.zipp.ai.domain.material.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

import java.util.Objects;

/** Authenticated owner identity used by long-lived material and chartbook operations. */
public record CatalogOwner(OwnerType ownerType, String ownerKey) {
    public CatalogOwner {
        ownerType = Objects.requireNonNull(ownerType, "ownerType");
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey is required");
        }
        ownerKey = ownerKey.trim();
    }

    public void requireRegisteredUser() {
        if (ownerType != OwnerType.USER) {
            throw new CatalogOperationException(CatalogErrorCode.REGISTERED_USER_REQUIRED);
        }
    }
}
