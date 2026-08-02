package org.zipp.ai.application.memory;

/** Tenant-fenced scope for one long-term Memory item. */
public record AutoMemoryScope(
        String ownerKey,
        MemoryScopeType type,
        String scopeKey
) {
    public AutoMemoryScope {
        ownerKey = required(ownerKey, "ownerKey");
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        scopeKey = required(scopeKey, "scopeKey");
        // A user-global scope must not be usable to address another tenant.
        if (type == MemoryScopeType.USER && !ownerKey.equals(scopeKey)) {
            throw new IllegalArgumentException("USER scopeKey must equal ownerKey");
        }
    }

    public static AutoMemoryScope user(String ownerKey) {
        return new AutoMemoryScope(ownerKey, MemoryScopeType.USER, ownerKey);
    }

    public static AutoMemoryScope chartbook(String ownerKey, String chartbookId) {
        return new AutoMemoryScope(ownerKey, MemoryScopeType.CHARTBOOK, chartbookId);
    }

    public String chartbookId() {
        return type == MemoryScopeType.CHARTBOOK ? scopeKey : null;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
