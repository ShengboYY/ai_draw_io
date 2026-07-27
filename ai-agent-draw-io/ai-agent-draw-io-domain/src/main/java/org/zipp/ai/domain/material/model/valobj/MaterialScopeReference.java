package org.zipp.ai.domain.material.model.valobj;

/** Content-free reference describing where one owned material may be retrieved. */
public record MaterialScopeReference(String linkId, MaterialScopeType scopeType, String scopeKey) {
    public MaterialScopeReference {
        linkId = required(linkId, "linkId");
        if (scopeType == null) throw new IllegalArgumentException("scopeType is required");
        scopeKey = required(scopeKey, "scopeKey");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
