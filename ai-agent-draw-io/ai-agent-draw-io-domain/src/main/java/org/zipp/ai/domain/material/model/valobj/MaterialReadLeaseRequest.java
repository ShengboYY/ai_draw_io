package org.zipp.ai.domain.material.model.valobj;

/** Exact authorized source requested by one grounded run. */
public record MaterialReadLeaseRequest(CatalogOwner owner, String materialId,
                                       String versionId, String revisionId, String runId,
                                       MaterialScopeType scopeType, String scopeKey,
                                       boolean partialReadyAccepted) {
    public MaterialReadLeaseRequest {
        if (owner == null || scopeType == null) throw new IllegalArgumentException("owner/scope required");
        materialId = required(materialId, "materialId");
        versionId = required(versionId, "versionId");
        revisionId = required(revisionId, "revisionId");
        runId = required(runId, "runId");
        scopeKey = scopeType == MaterialScopeType.LIBRARY
                ? MaterialScopeType.PERSONAL_LIBRARY_KEY : required(scopeKey, "scopeKey");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
