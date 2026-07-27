package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;

/** One exact source version/revision that passed owner and scope authorization. */
public record AuthorizedSource(String materialId, String versionId, String revisionId,
                               MaterialScopeType scopeType, String scopeKey, String state,
                               boolean conversationScoped, boolean required,
                               boolean hasText, boolean hasVisual) {
    public boolean ready() {
        return "READY".equals(state) || "PARTIAL_READY".equals(state);
    }

    public boolean partialReady() {
        return "PARTIAL_READY".equals(state);
    }
}
