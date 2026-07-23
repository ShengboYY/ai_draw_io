package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;

import java.util.Objects;

/** One exact material version/revision fixed by the server for the lifetime of a run. */
public record ResolvedSource(String materialId, String versionId, String revisionId, String kind,
                             MaterialScopeType scopeType, String scopeKey, String state,
                             RequestSourceOrigin origin, boolean hasText, boolean hasVisual,
                             boolean pinned) {
    public ResolvedSource {
        materialId = required(materialId, "materialId");
        versionId = required(versionId, "versionId");
        revisionId = required(revisionId, "revisionId");
        kind = required(kind, "kind");
        Objects.requireNonNull(scopeType, "scopeType");
        scopeKey = required(scopeKey, "scopeKey");
        state = required(state, "state");
        Objects.requireNonNull(origin, "origin");
    }

    public boolean ready() {
        return "READY".equals(state) || "PARTIAL_READY".equals(state);
    }

    public boolean declared() {
        return origin == RequestSourceOrigin.ATTACHMENT || origin == RequestSourceOrigin.EXPLICIT;
    }

    public AuthorizedSource authorizedSource() {
        return new AuthorizedSource(materialId, versionId, revisionId, scopeType, scopeKey, state,
                scopeType == MaterialScopeType.CONVERSATION, declared(), hasText, hasVisual);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
