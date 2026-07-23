package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;

/** Owner- and scope-authorized database projection used only while fixing a request snapshot. */
public record SourceResolutionCandidate(String declarationId, String materialId, String versionId,
                                        String revisionId, String kind, MaterialScopeType scopeType,
                                        String scopeKey, String state, String uploadState,
                                        boolean conversationScoped, boolean hasText,
                                        boolean hasVisual, boolean pinned) {
    public boolean ready() {
        return ("".equals(uploadState) || "SUCCEEDED".equals(uploadState))
                && ("READY".equals(state) || "PARTIAL_READY".equals(state));
    }

    public boolean processing() {
        return "CREATED".equals(uploadState) || "OBJECT_VERSION_PINNED".equals(uploadState)
                || "PROCESSING".equals(uploadState) || "PROCESSING".equals(state);
    }
}
