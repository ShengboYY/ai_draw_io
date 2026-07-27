package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

public interface EvidenceReadLeaseCoordinator {
    /** Returns one close handle for an atomically acquired source set. */
    AutoCloseable acquire(CatalogOwner owner, String runId, AuthorizedSourceSet sources);
}
