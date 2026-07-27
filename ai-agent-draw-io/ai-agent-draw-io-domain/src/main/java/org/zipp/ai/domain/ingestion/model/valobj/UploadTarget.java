package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import java.util.Objects;

public record UploadTarget(MaterialScopeType scopeType, String scopeKey, RetentionClass retentionClass) {

    public UploadTarget {
        Objects.requireNonNull(scopeType, "scopeType");
        scopeKey = requireText(scopeKey, "scopeKey");
        if (scopeKey.length() > 160) {
            throw new IllegalArgumentException("scopeKey exceeds its maximum length");
        }
        Objects.requireNonNull(retentionClass, "retentionClass");
        boolean temporaryConversation = retentionClass == RetentionClass.TEMPORARY
                && scopeType == MaterialScopeType.CONVERSATION;
        boolean retainedScope = retentionClass == RetentionClass.RETAINED
                && scopeType != MaterialScopeType.CONVERSATION;
        if (!temporaryConversation && !retainedScope) {
            throw new IllegalArgumentException("temporary uploads require a conversation; retained uploads require a durable scope");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
