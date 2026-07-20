package org.zipp.ai.domain.material.model.valobj;

import java.util.Objects;

public final class CatalogOperationException extends RuntimeException {
    private final CatalogErrorCode code;

    public CatalogOperationException(CatalogErrorCode code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public CatalogErrorCode code() {
        return code;
    }
}
