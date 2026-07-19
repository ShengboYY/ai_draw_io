package org.zipp.ai.domain.material.model.valobj;

import java.util.Objects;

public record MaterialScopeLink(MaterialScopeType scopeType, String scopeKey, String createdBy) {

    public MaterialScopeLink {
        Objects.requireNonNull(scopeType, "scopeType");
        scopeKey = requireText(scopeKey, "scopeKey");
        createdBy = requireText(createdBy, "createdBy");
    }

    public static MaterialScopeLink of(MaterialScopeType scopeType, String scopeKey, String createdBy) {
        return new MaterialScopeLink(scopeType, scopeKey, createdBy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
