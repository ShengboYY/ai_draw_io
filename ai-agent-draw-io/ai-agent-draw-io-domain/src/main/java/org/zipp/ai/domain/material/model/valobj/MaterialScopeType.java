package org.zipp.ai.domain.material.model.valobj;

public enum MaterialScopeType {
    LIBRARY,
    DIAGRAM,
    CHARTBOOK,
    CONVERSATION;

    public static final String PERSONAL_LIBRARY_KEY = "personal";

    public static boolean isPersonalLibraryKey(String scopeKey, String ownerKey) {
        // `library` is the documented alias; ownerKey preserves early-upload compatibility.
        return PERSONAL_LIBRARY_KEY.equals(scopeKey) || "library".equals(scopeKey)
                || ownerKey.equals(scopeKey);
    }
}
