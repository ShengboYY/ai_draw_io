package org.zipp.ai.infrastructure.adapter.repository;

import org.zipp.ai.domain.material.model.aggregate.EvidenceReadLease;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialReadLeaseRequest;
import org.zipp.ai.domain.material.service.MaterialReadLeaseService;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;

import java.util.List;
import java.util.Objects;

/** Adapts WP4C's atomic batch authorization into one run-scoped close handle. */
public final class MaterialEvidenceReadLeaseCoordinator implements EvidenceReadLeaseCoordinator {
    private final MaterialReadLeaseService leases;

    public MaterialEvidenceReadLeaseCoordinator(MaterialReadLeaseService leases) {
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Override
    public AutoCloseable acquire(CatalogOwner owner, String runId, AuthorizedSourceSet sources) {
        List<MaterialReadLeaseRequest> requests = sources.sources().stream().map(source ->
                new MaterialReadLeaseRequest(owner, source.materialId(), source.versionId(), source.revisionId(),
                        runId, source.scopeType(), source.scopeKey(), false)).toList();
        List<EvidenceReadLease> acquired = leases.acquireAll(requests);
        return () -> acquired.forEach(lease -> releaseQuietly(owner, lease, runId));
    }

    private void releaseQuietly(CatalogOwner owner, EvidenceReadLease lease, String runId) {
        try {
            leases.release(owner, lease.id(), runId);
        } catch (RuntimeException ignored) {
            // Lease expiry is an acceptable cleanup race; lifecycle cleanup remains authoritative.
        }
    }
}
